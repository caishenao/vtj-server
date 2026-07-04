package cn.cai.vtjserver.module.ai.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Persisted LLM provider configuration. {@code apiKey} is stored encrypted (AES-GCM); it is never
 * exposed in plaintext through DTOs. {@code type} drives scenario routing
 * ({@code MULTIMODAL} for UI/design generation, {@code CODING} for logic/code generation).
 */
@Data
@TableName("vtj_llm_config")
public class LlmConfigEntity {
    @TableId
    private String id;
    private String name;
    private String type;
    private String provider;
    private String apiKey;
    private String baseUrl;
    private String model;
    private Boolean enabled;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
