package cn.cai.vtjserver.module.ai.dto;

/**
 * One frame relayed to the frontend over SSE. The field shape ({@code id, topicId, content,
 * finish}) is preserved exactly from the historical stub contract; {@code content} carries an
 * incremental delta (OpenAI streaming semantics), and the terminal frame has {@code finish=true}.
 */
public record AiStreamEvent(String id, String topicId, String content, boolean finish) {

    public static AiStreamEvent delta(String id, String topicId, String content) {
        return new AiStreamEvent(id, topicId, content, false);
    }

    public static AiStreamEvent finish(String id, String topicId) {
        return new AiStreamEvent(id, topicId, "", true);
    }
}
