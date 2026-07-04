package cn.cai.vtjserver.module.ai.dto;

import cn.cai.vtjserver.module.ai.entity.LlmConfigEntity;

import java.time.OffsetDateTime;

/**
 * Outbound view of an LLM configuration. The {@code apiKey} is never returned; instead a boolean
 * {@code hasApiKey} flag tells the UI whether a key is configured (AGENTS.md §5.6 — secrets are
 * never exposed).
 */
public record LlmConfigDto(
        String id,
        String name,
        String type,
        String provider,
        boolean hasApiKey,
        String baseUrl,
        String model,
        Boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static LlmConfigDto from(LlmConfigEntity e) {
        return new LlmConfigDto(
                e.getId(), e.getName(), e.getType(), e.getProvider(),
                e.getApiKey() != null && !e.getApiKey().isBlank(),
                e.getBaseUrl(), e.getModel(), e.getEnabled(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
