package cn.cai.vtjserver.module.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Create/update payload for an LLM configuration. {@code apiKey} is optional on update: a blank
 * value means "keep the stored key unchanged" so the plaintext key never has to round-trip through
 * the client to be re-saved.
 */
public record LlmConfigRequest(
        String id,
        @NotBlank String name,
        @NotBlank String type,
        String provider,
        String apiKey,
        @NotBlank String baseUrl,
        @NotBlank String model,
        Boolean enabled) {
}
