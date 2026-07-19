package cn.cai.vtjserver.module.ai.service;

import cn.cai.vtjserver.config.VtjProperties;
import cn.cai.vtjserver.module.ai.dto.AiScene;
import cn.cai.vtjserver.module.ai.dto.AiStreamEvent;
import cn.cai.vtjserver.module.ai.dto.ChatCompletionRequest;
import cn.cai.vtjserver.module.ai.entity.AiTopicEntity;
import cn.cai.vtjserver.module.ai.entity.LlmConfigEntity;
import cn.cai.vtjserver.module.ai.mapper.AiTopicMapper;
import cn.cai.vtjserver.module.ai.provider.OpenAiCompatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAgentServiceTest {

    @Mock
    private AiTopicMapper topicMapper;
    @Mock
    private LlmConfigService llmConfigService;
    @Mock
    private OpenAiCompatClient client;

    private AiAgentService service;

    @BeforeEach
    void setUp() {
        service = new AiAgentService(topicMapper, llmConfigService, client, new VtjProperties());
    }

    private AiTopicEntity topic(String id, String prompt, AiScene scene) {
        AiTopicEntity t = new AiTopicEntity();
        t.setId(id);
        t.setPrompt(prompt);
        t.setScene(scene.name());
        return t;
    }

    private LlmConfigEntity config() {
        LlmConfigEntity c = new LlmConfigEntity();
        c.setBaseUrl("https://api.example.com/v1");
        c.setApiKey("sk-decrypted");
        c.setModel("gpt-4o");
        c.setEnabled(true);
        return c;
    }

    @Test
    void streamsDeltasThenTerminalFinishEvent() {
        when(topicMapper.selectById("t1")).thenReturn(topic("t1", "make a button", AiScene.CODING));
        when(llmConfigService.resolveForScene(AiScene.CODING)).thenReturn(Optional.of(config()));
        when(client.streamChat(anyString(), anyString(), any(ChatCompletionRequest.class), any(Duration.class)))
                .thenReturn(Flux.just("Hello", " world"));

        List<AiStreamEvent> events = service.buildEventStream("t1", "m1").collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).content()).isEqualTo("Hello");
        assertThat(events.get(0).finish()).isFalse();
        assertThat(events.get(1).content()).isEqualTo(" world");
        assertThat(events.get(2).finish()).isTrue();
        assertThat(events).allMatch(e -> e.topicId().equals("t1") && e.id().equals("m1"));
    }

    @Test
    void fallsBackToEmptyFinishWhenNoEnabledConfig() {
        when(topicMapper.selectById("t1")).thenReturn(topic("t1", "make a button", AiScene.MULTIMODAL));
        when(llmConfigService.resolveForScene(AiScene.MULTIMODAL)).thenReturn(Optional.empty());

        List<AiStreamEvent> events = service.buildEventStream("t1", "m1").collectList().block();

        assertThat(events).singleElement()
                .satisfies(e -> {
                    assertThat(e.finish()).isTrue();
                    assertThat(e.content()).isEmpty();
                });
    }

    @Test
    void fallsBackWhenTopicMissing() {
        when(topicMapper.selectById("missing")).thenReturn(null);

        List<AiStreamEvent> events = service.buildEventStream("missing", "m1").collectList().block();

        assertThat(events).singleElement().satisfies(e -> assertThat(e.finish()).isTrue());
    }

    @Test
    void terminatesCleanlyWhenProviderErrors() {
        when(topicMapper.selectById("t1")).thenReturn(topic("t1", "boom", AiScene.CODING));
        when(llmConfigService.resolveForScene(AiScene.CODING)).thenReturn(Optional.of(config()));
        when(client.streamChat(anyString(), anyString(), any(ChatCompletionRequest.class), any(Duration.class)))
                .thenReturn(Flux.error(new RuntimeException("upstream 500")));

        List<AiStreamEvent> events = service.buildEventStream("t1", "m1").collectList().block();

        // Error is swallowed into a clean terminal frame so the frontend stream closes normally.
        assertThat(events).singleElement().satisfies(e -> assertThat(e.finish()).isTrue());
    }

    @Test
    void saveTopicPersistsPromptAndScene() {
        when(topicMapper.selectById(anyString())).thenReturn(null);

        AiTopicEntity saved = service.saveTopic(Map.of(
                "id", "t9", "prompt", "design a login page", "scene", "multimodal"));

        assertThat(saved.getId()).isEqualTo("t9");
        assertThat(saved.getPrompt())
                .contains("# VTJ.PRO Agent Task")
                .contains("design a login page")
                .contains("Staged page generation");
        assertThat(saved.getScene()).isEqualTo("MULTIMODAL");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createTopicPayloadMatchesFrontendTopicAndChatContract() {
        when(topicMapper.selectById(anyString())).thenReturn(null);

        Map<String, Object> payload = service.createTopicPayload(Map.of(
                "prompt", "make a compact dashboard",
                "model", "gpt-x"));

        assertThat(payload).containsKeys("topic", "chat");
        Map<String, Object> topic = (Map<String, Object>) payload.get("topic");
        Map<String, Object> chat = (Map<String, Object>) payload.get("chat");
        assertThat(topic)
                .containsEntry("prompt", "make a compact dashboard")
                .containsEntry("model", "gpt-x")
                .containsEntry("type", "text");
        assertThat(chat)
                .containsEntry("prompt", "make a compact dashboard")
                .containsEntry("status", "Pending");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ssePayloadKeepsLegacyFieldsAndAddsOpenAiChunkFields() {
        Map<String, Object> payload = service.toSsePayload(AiStreamEvent.delta("m1", "t1", "Hello"));

        assertThat(payload)
                .containsEntry("id", "m1")
                .containsEntry("topicId", "t1")
                .containsEntry("content", "Hello")
                .containsEntry("finish", false);

        List<Map<String, Object>> choices = (List<Map<String, Object>>) payload.get("choices");
        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
        assertThat(delta).containsEntry("content", "Hello");
    }

    @Test
    void sseDataStartsWithSpaceForFrontendLineParser() {
        String data = service.toSseData(AiStreamEvent.delta("m1", "t1", "Hello"));

        assertThat(data).startsWith(" {");
    }

    @Test
    void createChatPayloadRefreshesTopicPromptForCompletions() {
        AiTopicEntity existing = topic("t1", "old prompt", AiScene.CODING);
        when(topicMapper.selectById("t1")).thenReturn(existing);

        Map<String, Object> chat = service.createChatPayload(Map.of(
                "topicId", "t1",
                "prompt", "new prompt"));

        assertThat(chat).containsEntry("topicId", "t1")
                .containsEntry("prompt", "new prompt");
        assertThat(existing.getPrompt())
                .contains("# VTJ.PRO Agent Task")
                .contains("new prompt");
        verify(topicMapper).updateById(existing);
    }

    @Test
    void createChatPayloadPreservesOriginalTaskForStagedFollowups() {
        AiTopicEntity existing = topic("t1",
                "# VTJ.PRO Agent Task\n## User task\nmake an official website homepage\n",
                AiScene.CODING);
        when(topicMapper.selectById("t1")).thenReturn(existing);

        service.createChatPayload(Map.of(
                "topicId", "t1",
                "prompt", "O: vue execution succeeded",
                "toolCallId", "vue",
                "source", "<template><main class=\"site-home\">layout</main></template>"));

        assertThat(existing.getPrompt())
                .contains("Previous objective and stage context")
                .contains("make an official website homepage")
                .contains("Current Vue source")
                .contains("site-home");
        verify(topicMapper).updateById(existing);
    }

    @Test
    void composeAgentPromptIncludesToolsAndDslContext() {
        String prompt = service.composeAgentPrompt(Map.of(
                "prompt", "bind click event",
                "dsl", "{\"nodes\":[]}",
                "tools", "[{\"name\":\"setApi\"}]",
                "source", "<template></template>"),
                "bind click event");

        assertThat(prompt)
                .contains("bind click event")
                .contains("Current page DSL")
                .contains("Registered frontend tool signatures")
                .contains("\"setApi\"");
    }

    @Test
    void composeAgentPromptDeclaresPageComponentApiAndJavascriptCapabilities() {
        String prompt = service.composeAgentPrompt(Map.of(), "build an interactive admin page");

        assertThat(prompt)
                .contains("Capability routing")
                .contains("createPage")
                .contains("createBlock")
                .contains("setApi")
                .contains("setGlobalStore")
                .contains("JavaScript and application behavior")
                .contains("refresh");
    }

    @Test
    void composeAgentPromptLocksChangesToSelectedComponent() {
        Map<String, Object> selection = Map.of(
                "nodeId", "button-1",
                "name", "ElButton",
                "path", List.of("div#layout", "ElButton#button-1"),
                "dsl", Map.of(
                        "id", "button-1",
                        "name", "ElButton",
                        "from", "element-plus",
                        "props", Map.of("type", "primary"),
                        "children", "Save"));

        String prompt = service.composeAgentPrompt(Map.of("selection", selection), "make the button green");

        assertThat(prompt)
                .contains("Immutable selected-component scope")
                .contains("Selected component scope")
                .contains("vtj-node")
                .contains("button-1")
                .contains("Never return page-level `vue`/`diff`")
                .contains("JSFunction");
    }

    @Test
    void createChatPayloadEchoesSelectionForScopedApplication() {
        AiTopicEntity existing = topic("t1", "existing prompt", AiScene.CODING);
        when(topicMapper.selectById("t1")).thenReturn(existing);
        Map<String, Object> selection = Map.of("nodeId", "button-1", "name", "ElButton");

        Map<String, Object> chat = service.createChatPayload(Map.of(
                "topicId", "t1",
                "prompt", "change color",
                "selection", selection));

        assertThat(chat).containsEntry("selection", selection);
    }

    @Test
    void oversizedContextKeepsToolSignaturesAndCurrentSource() {
        VtjProperties limitedProperties = new VtjProperties();
        limitedProperties.getAi().setMaxContextChars(6500);
        service = new AiAgentService(topicMapper, llmConfigService, client, limitedProperties);
        String longDescription = "tool-description-".repeat(300);
        String tools = """
                [
                  {"name":"createPage","description":"%s","parameters":[{"name":"page","type":"object","required":true}]},
                  {"name":"createBlock","description":"%s","parameters":[{"name":"block","type":"object","required":true}]},
                  {"name":"setApi","description":"%s","parameters":[{"name":"api","type":"object","required":true}]}
                ]
                """.formatted(longDescription, longDescription, longDescription);

        String prompt = service.composeAgentPrompt(Map.of(
                "tools", tools,
                "source", "<template><main>LIVE-PREVIEW-SOURCE</main></template>" + "x".repeat(6000),
                "dsl", "d".repeat(12000),
                "project", "p".repeat(12000)),
                "create a page and wire its API");

        assertThat(prompt)
                .hasSizeLessThanOrEqualTo(6500)
                .contains("- createPage(page:object)")
                .contains("- createBlock(block:object)")
                .contains("- setApi(api:object)")
                .contains("LIVE-PREVIEW-SOURCE")
                .contains("Some context was compacted");
    }
}
