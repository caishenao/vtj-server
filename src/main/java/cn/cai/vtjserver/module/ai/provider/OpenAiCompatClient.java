package cn.cai.vtjserver.module.ai.provider;

import cn.cai.vtjserver.module.ai.dto.ChatCompletionRequest;
import cn.cai.vtjserver.util.Jsons;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/**
 * Thin client that speaks the OpenAI Chat Completions streaming protocol against any compatible
 * endpoint (OpenAI, DeepSeek, Zhipu, openrouter, private deployments). It relays incremental
 * {@code choices[].delta.content} tokens as a {@link Flux} of plain strings; the caller decides how
 * to frame them for the frontend.
 *
 * <p>The client is transport-only: it holds no credentials or model state. Every call receives the
 * resolved {@code baseUrl}, {@code apiKey} (already decrypted) and request from the service layer.
 */
@Slf4j
@Component
public class OpenAiCompatClient {
    private static final String DONE_MARKER = "[DONE]";

    private final WebClient.Builder webClientBuilder;

    public OpenAiCompatClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * Opens a streaming chat completion.
     *
     * @param baseUrl provider base URL (without the {@code /chat/completions} suffix)
     * @param apiKey  decrypted bearer token; may be blank for keyless private endpoints
     * @param request the chat request (should have {@code stream=true})
     * @param timeout overall stream timeout; on expiry the stream errors and is cancelled upstream
     * @return a stream of content deltas in arrival order
     */
    public Flux<String> streamChat(String baseUrl, String apiKey, ChatCompletionRequest request, Duration timeout) {
        WebClient client = webClientBuilder.baseUrl(normalizeBase(baseUrl)).build();
        return client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (apiKey != null && !apiKey.isBlank()) {
                        headers.setBearerAuth(apiKey);
                    }
                })
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(timeout)
                .takeUntil(line -> line != null && line.contains(DONE_MARKER))
                .mapNotNull(this::extractDelta)
                .filter(delta -> !delta.isEmpty());
    }

    /**
     * Parses a single OpenAI SSE data line into its content delta.
     *
     * <p>Spring strips the {@code data:} prefix when decoding {@code text/event-stream}, so lines
     * arrive as raw JSON. Non-JSON lines, the {@code [DONE]} marker and deltas without content yield
     * an empty string and are filtered out by the caller.
     */
    private String extractDelta(String line) {
        if (line == null || line.isBlank() || line.contains(DONE_MARKER)) {
            return "";
        }
        String json = line.startsWith("data:") ? line.substring("data:".length()).trim() : line.trim();
        if (!json.startsWith("{")) {
            return "";
        }
        try {
            Map<String, Object> parsed = Jsons.parseMap(json);
            Object choices = parsed.get("choices");
            if (choices instanceof java.util.List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> choice
                    && choice.get("delta") instanceof Map<?, ?> delta
                    && delta.get("content") instanceof String content) {
                return content;
            }
        } catch (Exception e) {
            // A malformed chunk must not kill the whole stream; skip it and keep relaying.
            log.debug("Skipping unparseable stream chunk: {}", e.getMessage());
        }
        return "";
    }

    private String normalizeBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("LLM baseUrl must not be blank");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
