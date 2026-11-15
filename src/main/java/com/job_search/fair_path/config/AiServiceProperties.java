package com.job_search.fair_path.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Transport settings for every Spring → AI service call (fairpath.ai-service.*). */
@ConfigurationProperties("fairpath.ai-service")
public record AiServiceProperties(
        String baseUrl,
        String internalToken,
        @DefaultValue("5s") Duration connectTimeout,
        // Placeholder until hardening sets it from measured latency.
        @DefaultValue("120s") Duration readTimeout,
        // The wait before Analyze's single retry of a refused connection or a bare 503.
        @DefaultValue("5s") Duration retryDelay) {
}
