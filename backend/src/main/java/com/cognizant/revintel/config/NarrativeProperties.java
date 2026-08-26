package com.cognizant.revintel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the single LLM call site.
 *
 * <p>{@code anthropicApiKey} blank is a supported, fully-functional configuration -- not a
 * degraded one. The app then skips the model entirely and renders template narration, which is
 * also the path any ungrounded generation falls back to.
 */
@ConfigurationProperties(prefix = "revintel.narrative")
public record NarrativeProperties(
        String anthropicApiKey,
        String model,
        Integer maxTokens,
        String effort,
        Integer timeoutSeconds) {

    public NarrativeProperties {
        model = (model == null || model.isBlank()) ? "claude-opus-5" : model;
        maxTokens = (maxTokens == null || maxTokens <= 0) ? 1024 : maxTokens;
        effort = (effort == null || effort.isBlank()) ? "low" : effort;
        timeoutSeconds = (timeoutSeconds == null || timeoutSeconds <= 0) ? 20 : timeoutSeconds;
    }

    public boolean llmEnabled() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }
}
