package com.job_search.fair_path.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Size of each feature's AI pool (fairpath.ai-execution.*): worker threads and how many requests may wait. */
@ConfigurationProperties("fairpath.ai-execution")
public record AiExecutionProperties(
        @DefaultValue("8") int analysisThreads,
        @DefaultValue("8") int comparisonThreads,
        /** Requests that may wait for a free thread; one more is rejected with AI_BUSY. Zero means no waiting. */
        @DefaultValue("16") int analysisQueueCapacity,
        @DefaultValue("16") int comparisonQueueCapacity) {
}
