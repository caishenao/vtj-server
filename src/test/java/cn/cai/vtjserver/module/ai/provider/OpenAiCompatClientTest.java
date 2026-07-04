package cn.cai.vtjserver.module.ai.provider;

import cn.cai.vtjserver.module.ai.dto.ChatCompletionRequest;
import cn.cai.vtjserver.module.ai.dto.ChatCompletionRequest.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link OpenAiCompatClient} parses OpenAI streaming SSE frames into content deltas,
 * using a stubbed {@link ExchangeFunction} so no real model endpoint is contacted (AGENTS.md §9).
 */
class OpenAiCompatClientTest {

    private OpenAiCompatClient clientReturning(String sseBody) {
        ExchangeFunction stub = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "text/event-stream")
                .body(sseBody)
                .build());
        WebClient.Builder builder = WebClient.builder().exchangeFunction(stub);
        return new OpenAiCompatClient(builder);
    }

    @Test
    void parsesContentDeltasAndStopsAtDone() {
        String sse = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":", world"}}]}

                data: [DONE]

                """;
        OpenAiCompatClient client = clientReturning(sse);

        List<String> deltas = client.streamChat(
                        "https://api.example.com/v1", "sk-test",
                        ChatCompletionRequest.streaming("gpt-x", List.of(ChatMessage.user("hi"))),
                        Duration.ofSeconds(5))
                .collectList()
                .block();

        assertThat(deltas).containsExactly("Hello", ", world");
    }

    @Test
    void emptyAndRoleOnlyDeltasAreFilteredOut() {
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{"content":""}}]}

                data: {"choices":[{"delta":{"content":"real"}}]}

                data: [DONE]

                """;
        OpenAiCompatClient client = clientReturning(sse);

        List<String> deltas = client.streamChat(
                        "https://api.example.com/v1/", "",
                        ChatCompletionRequest.streaming("gpt-x", List.of(ChatMessage.user("hi"))),
                        Duration.ofSeconds(5))
                .collectList()
                .block();

        assertThat(deltas).containsExactly("real");
    }
}
