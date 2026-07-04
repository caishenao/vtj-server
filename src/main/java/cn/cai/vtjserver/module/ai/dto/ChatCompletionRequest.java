package cn.cai.vtjserver.module.ai.dto;

import java.util.List;

/**
 * Minimal OpenAI Chat Completions request payload. {@code stream} is always {@code true} in this
 * gateway because responses are relayed to the frontend over SSE.
 *
 * @param model    the target model name
 * @param messages the conversation messages
 * @param stream   whether to request a streamed response
 */
public record ChatCompletionRequest(String model, List<ChatMessage> messages, boolean stream) {

    public static ChatCompletionRequest streaming(String model, List<ChatMessage> messages) {
        return new ChatCompletionRequest(model, messages, true);
    }

    /**
     * A single chat message.
     *
     * @param role    one of {@code system} / {@code user} / {@code assistant}
     * @param content the message text
     */
    public record ChatMessage(String role, String content) {
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }
    }
}
