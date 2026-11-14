package com.job_search.fair_path.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Limits on running an Analysis (fairpath.analysis.*). Config, not code: retuning changes a value. */
@ConfigurationProperties("fairpath.analysis")
public record AnalysisProperties(
        /** Minimum wait after a completed Analysis before the next one may start. */
        @DefaultValue("24h") Duration cooldown) {
}
