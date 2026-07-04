package cn.cai.vtjserver.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * A persisted AI conversation topic. The frontend creates a topic (carrying the prompt) and then
 * opens the completions SSE stream with only the topic id, so the prompt and routing scene must be
 * stored server-side to be retrievable at stream time.
 */
@Data
@TableName("vtj_ai_topic")
public class AiTopicEntity {
    @TableId
    private String id;
    private String prompt;
    /** Routing scene: {@code MULTIMODAL} or {@code CODING}. */
    private String scene;
    private String model;
    private OffsetDateTime createdAt;
}
