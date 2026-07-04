package cn.cai.vtjserver.module.ai.service;

import cn.cai.vtjserver.config.VtjProperties;
import cn.cai.vtjserver.module.ai.dto.AiScene;
import cn.cai.vtjserver.module.ai.dto.AiStreamEvent;
import cn.cai.vtjserver.module.ai.dto.ChatCompletionRequest;
import cn.cai.vtjserver.module.ai.dto.ChatCompletionRequest.ChatMessage;
import cn.cai.vtjserver.module.ai.entity.AiTopicEntity;
import cn.cai.vtjserver.module.ai.entity.LlmConfigEntity;
import cn.cai.vtjserver.module.ai.mapper.AiTopicMapper;
import cn.cai.vtjserver.module.ai.provider.OpenAiCompatClient;
import cn.cai.vtjserver.util.Jsons;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the AI generation flow: persists conversation topics and relays a configured LLM's
 * streamed response to the frontend over SSE, preserving the {@code {id, topicId, content, finish}}
 * event contract.
 *
 * <p>When no enabled model matches the topic's scene, it degrades gracefully to a single empty
 * terminal event, identical to the pre-existing stub, so an unconfigured deployment keeps working
 * without errors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentService {
    private final AiTopicMapper topicMapper;
    private final LlmConfigService llmConfigService;
    private final OpenAiCompatClient client;
    private final VtjProperties properties;

    /**
     * Persists a topic from a posted body, extracting the prompt, routing scene and model
     * defensively (the exact frontend payload shape is not pinned).
     *
     * @return the stored topic, with a generated id when none was supplied
     */
    public AiTopicEntity saveTopic(Map<String, Object> body) {
        Map<String, Object> data = body == null ? Map.of() : body;
        String userPrompt = extractPrompt(data);
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(Jsons.text(data, "id", UUID.randomUUID().toString().replace("-", "")));
        topic.setPrompt(composeAgentPrompt(data, userPrompt));
        String scene = Jsons.text(data, "scene", Jsons.text(data, "type", AiScene.CODING.name()));
        topic.setScene(AiScene.from(scene).name());
        topic.setModel(Jsons.text(data, "model", ""));
        topic.setCreatedAt(OffsetDateTime.now());
        if (topicMapper.selectById(topic.getId()) == null) {
            topicMapper.insert(topic);
        } else {
            topicMapper.updateById(topic);
        }
        return topic;
    }

    /**
     * Creates the VTJ frontend's expected topic bootstrap payload. The frontend immediately
     * destructures {@code data.topic} and {@code data.chat}, then opens the SSE stream by topic id.
     */
    public Map<String, Object> createTopicPayload(Map<String, Object> body) {
        Map<String, Object> data = body == null ? Map.of() : body;
        String userPrompt = extractPrompt(data);
        AiTopicEntity entity = saveTopic(data);
        Map<String, Object> topic = toTopicPayload(entity, data);
        Map<String, Object> chatInput = new LinkedHashMap<>();
        chatInput.put("topicId", entity.getId());
        chatInput.put("prompt", userPrompt);
        chatInput.put("source", Jsons.text(data, "source", ""));
        chatInput.put("skipTopicRefresh", true);
        Map<String, Object> chat = createChatPayload(chatInput);
        return Map.of("topic", topic, "chat", chat);
    }

    /**
     * Creates a frontend-compatible chat object for a user message.
     */
    public Map<String, Object> createChatPayload(Map<String, Object> body) {
        Map<String, Object> data = body == null ? Map.of() : body;
        OffsetDateTime now = OffsetDateTime.now();
        String topicId = Jsons.text(data, "topicId", "");
        String prompt = Jsons.text(data, "prompt", "");
        if (!Boolean.TRUE.equals(data.get("skipTopicRefresh"))) {
            refreshTopicPrompt(topicId, data, prompt);
        }

        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("id", Jsons.text(data, "id", UUID.randomUUID().toString().replace("-", "")));
        chat.put("topicId", topicId);
        chat.put("prompt", prompt);
        chat.put("source", Jsons.text(data, "source", ""));
        chat.put("content", "");
        chat.put("createdAt", now);
        chat.put("dsl", null);
        chat.put("message", "");
        chat.put("reasoning", "");
        chat.put("status", "Pending");
        chat.put("tokens", 0);
        chat.put("userId", "local");
        chat.put("userName", "Local Developer");
        chat.put("thinking", 0);
        chat.put("vue", "");
        String toolCallId = Jsons.text(data, "toolCallId", "");
        if (!toolCallId.isBlank()) {
            chat.put("toolCallId", toolCallId);
        }
        return chat;
    }

    /**
     * Builds the reactive event stream for a completion. Kept separate from the SSE bridge so the
     * routing/fallback/terminal-frame logic is unit-testable without a servlet container.
     */
    public Flux<AiStreamEvent> buildEventStream(String topicId, String messageId) {
        AiTopicEntity topic = topicId == null || topicId.isBlank() ? null : topicMapper.selectById(topicId);
        String prompt = topic == null ? null : topic.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            log.debug("No prompt for topic {}; emitting terminal-only stream", topicId);
            return Flux.just(AiStreamEvent.finish(messageId, topicId));
        }

        AiScene scene = AiScene.from(topic.getScene());
        Optional<LlmConfigEntity> configOpt = llmConfigService.resolveForScene(scene);
        if (configOpt.isEmpty()) {
            log.warn("No enabled LLM config for scene {}; falling back to empty completion", scene);
            return Flux.just(AiStreamEvent.finish(messageId, topicId));
        }

        LlmConfigEntity config = configOpt.get();
        Duration timeout = scene == AiScene.MULTIMODAL
                ? properties.getAi().getTimeoutMultimodal()
                : properties.getAi().getTimeoutCoding();
        ChatCompletionRequest request = ChatCompletionRequest.streaming(
                config.getModel(), List.of(
                        ChatMessage.system(agentSystemPrompt()),
                        ChatMessage.user(prompt)
                ));

        Flux<AiStreamEvent> deltas = client.streamChat(config.getBaseUrl(), config.getApiKey(), request, timeout)
                .map(content -> AiStreamEvent.delta(messageId, topicId, content));
        return Flux.concat(deltas, Flux.just(AiStreamEvent.finish(messageId, topicId)))
                .onErrorResume(err -> {
                    // A provider failure must still terminate the frontend stream cleanly.
                    log.error("AI stream failed for topic {}: {}", topicId, err.toString());
                    return Flux.just(AiStreamEvent.finish(messageId, topicId));
                });
    }

    /**
     * Bridges {@link #buildEventStream} onto a Spring MVC {@link SseEmitter}. The reactive
     * subscription runs on {@code boundedElastic} so it never blocks the servlet request thread, and
     * is disposed on completion/timeout/client-disconnect.
     */
    public SseEmitter stream(String topicId, String messageId) {
        long emitterTimeout = properties.getAi().getTimeoutMultimodal().toMillis() + 5_000L;
        SseEmitter emitter = new SseEmitter(emitterTimeout);

        Disposable subscription = buildEventStream(topicId, messageId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> emit(emitter, event),
                        error -> emitter.completeWithError(error),
                        emitter::complete);

        emitter.onTimeout(subscription::dispose);
        emitter.onCompletion(subscription::dispose);
        emitter.onError(e -> subscription.dispose());
        return emitter;
    }

    private void emit(SseEmitter emitter, AiStreamEvent event) {
        try {
            emitter.send(SseEmitter.event().data(toSseData(event)));
        } catch (IOException e) {
            // Client disconnected mid-stream; surface as an error so the subscription is disposed.
            throw new IllegalStateException("SSE client disconnected", e);
        }
    }

    String toSseData(AiStreamEvent event) {
        // VTJ's frontend parser accepts only lines prefixed with "data: " (with a space).
        return " " + Jsons.string(toSsePayload(event));
    }

    Map<String, Object> toSsePayload(AiStreamEvent event) {
        String id = event.id() == null ? "" : event.id();
        String topicId = event.topicId() == null ? "" : event.topicId();
        String content = event.content() == null ? "" : event.content();

        Map<String, Object> delta = new LinkedHashMap<>();
        if (!event.finish()) {
            delta.put("content", content);
        }

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", event.finish() ? "stop" : null);
        choice.put("logprobs", null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("topicId", topicId);
        payload.put("content", content);
        payload.put("finish", event.finish());
        payload.put("object", "chat.completion.chunk");
        payload.put("created", Instant.now().getEpochSecond());
        payload.put("model", "");
        payload.put("choices", List.of(choice));
        return payload;
    }

    private void refreshTopicPrompt(String topicId, Map<String, Object> data, String prompt) {
        if (topicId == null || topicId.isBlank() || prompt == null || prompt.isBlank()) {
            return;
        }
        AiTopicEntity topic = topicMapper.selectById(topicId);
        if (topic == null) {
            return;
        }
        String previousPrompt = shouldPreservePreviousContext(data, prompt) ? topic.getPrompt() : "";
        topic.setPrompt(composeAgentPrompt(data, prompt, previousPrompt));
        topicMapper.updateById(topic);
    }

    private Map<String, Object> toTopicPayload(AiTopicEntity entity, Map<String, Object> data) {
        String prompt = extractPrompt(data);
        OffsetDateTime createdAt = entity.getCreatedAt() == null ? OffsetDateTime.now() : entity.getCreatedAt();
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("id", entity.getId());
        topic.put("appId", "local");
        topic.put("createAt", createdAt);
        topic.put("createdAt", createdAt);
        topic.put("fileId", Jsons.text(data, "fileId", ""));
        topic.put("isHot", false);
        topic.put("model", entity.getModel() == null ? "" : entity.getModel());
        topic.put("platform", Jsons.text(data, "platform", "web"));
        topic.put("projectId", Jsons.text(data, "projectId", ""));
        topic.put("title", titleFromPrompt(prompt));
        topic.put("prompt", prompt);
        topic.put("dependencies", Jsons.text(data, "dependencies", ""));
        topic.put("dsl", data.get("dsl"));
        topic.put("type", Jsons.text(data, "type", "text"));
        return topic;
    }

    private String titleFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "New Chat";
        }
        String compact = prompt.replaceAll("\\s+", " ").trim();
        return compact.length() > 30 ? compact.substring(0, 30) : compact;
    }

    String composeAgentPrompt(Map<String, Object> data, String userPrompt) {
        return composeAgentPrompt(data, userPrompt, "");
    }

    String composeAgentPrompt(Map<String, Object> data, String userPrompt, String previousContext) {
        Map<String, Object> body = data == null ? Map.of() : data;
        int max = Math.max(4000, properties.getAi().getMaxContextChars());
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                # VTJ.PRO Agent Task

                You are operating the VTJ.PRO low-code designer. Your job is to output changes that
                the frontend Agent can execute, not ordinary chat.

                ## Output protocol
                - To execute a frontend tool, output `A:` followed by one json code block:
                  ```json
                  {"action":"toolName","parameters":["arg1","arg2"]}
                  ```
                - To generate the current page, output `A:` followed by one `vue` block with template/script/style.
                - To update the current page, output `A:` followed by one `diff` block.
                - To finish, output `F:`.
                - One response must contain at most one executable `A:` block.

                ## Staged page generation
                For page-building tasks such as landing pages, dashboards, official websites, or templates:
                - Do not attempt a full detailed page in one response.
                - First create a visible layout skeleton: header/nav, hero, primary sections, CTA/footer,
                  with stable class names and short placeholder content.
                - After the frontend returns `O:` success, update exactly one section/component per turn
                  using a `diff` block against the current Vue source.
                - Keep going section by section until the page is complete, then output `F:`.
                - If a section update fails, retry only that section instead of regenerating the whole page.

                ## User task
                """).append(blankTo(userPrompt, "No concrete task provided.")).append("\n\n");

        appendSection(prompt, "Previous objective and stage context", truncate(previousContext, 1800));
        appendSection(prompt, "Platform options", Jsons.text(body, "options", ""));
        appendSection(prompt, "Current page DSL", Jsons.text(body, "dsl", ""));
        appendSection(prompt, "Current project DSL", Jsons.text(body, "project", ""));
        appendSection(prompt, "Current Vue source", Jsons.text(body, "source", ""));
        appendSection(prompt, "Registered frontend tools JSON", Jsons.text(body, "tools", ""));
        appendSection(prompt, "Custom LLM config", Jsons.text(body, "llm", ""));

        String content = prompt.toString();
        if (content.length() <= max) {
            return content;
        }
        return content.substring(0, max)
                + "\n\n[Context truncated. Prefer getSkills/getCurrentFileContent/getApis tools for missing details.]";
    }

    private String agentSystemPrompt() {
        return """
                You are a VTJ.PRO low-code frontend Agent. Follow the VTJ Agent output protocol exactly.
                Prefer executable tool calls or Vue/diff blocks over prose when the user asks to change the app.
                For complex pages, work in stages: layout skeleton first, then one section/component per turn.
                Never expose secrets. Keep generated JavaScript deterministic and compatible with Vue 3 / VTJ DSL.
                """;
    }

    private void appendSection(StringBuilder prompt, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        prompt.append("## ").append(title).append('\n')
                .append(content).append("\n\n");
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean shouldPreservePreviousContext(Map<String, Object> data, String prompt) {
        String toolCallId = Jsons.text(data, "toolCallId", "");
        String text = prompt == null ? "" : prompt.trim();
        return !toolCallId.isBlank() || text.startsWith("O:");
    }

    private String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n[Previous context truncated.]";
    }

    @SuppressWarnings("unchecked")
    private String extractPrompt(Map<String, Object> data) {
        String direct = Jsons.text(data, "prompt", Jsons.text(data, "content", Jsons.text(data, "text", "")));
        if (!direct.isBlank()) {
            return direct;
        }
        // Fall back to the last user message when the payload uses OpenAI-style messages.
        Object messages = data.get("messages");
        if (messages instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i) instanceof Map<?, ?> msg && msg.get("content") instanceof String content
                        && !content.isBlank()) {
                    return content;
                }
            }
        }
        return "";
    }
}
