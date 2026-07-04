package cn.cai.vtjserver.module.ai.dto;

/**
 * Routing scenario for AI generation. UI/design-oriented requests use a multimodal model, while
 * logic/code generation uses a coding model (AGENTS.md §5.2).
 */
public enum AiScene {
    MULTIMODAL,
    CODING;

    /** Parses loosely (case-insensitive), defaulting to {@link #CODING} for unknown/blank input. */
    public static AiScene from(String value) {
        if (value == null || value.isBlank()) {
            return CODING;
        }
        try {
            return AiScene.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CODING;
        }
    }
}
