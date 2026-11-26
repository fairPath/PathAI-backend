package com.job_search.fair_path.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

/** Size of each feature's AI pool (fairpath.ai-execution.*): worker threads and how many requests may wait. */
@ConfigurationProperties("fairpath.ai-execution")
public record AiExecutionProperties(
        @DefaultValue("8") int analysisThreads,
        @DefaultValue("8") int comparisonThreads,
        /** Requests that may wait for a free thread; one more is rejected with AI_BUSY. Zero means no waiting. */
        @DefaultValue("16") int analysisQueueCapacity,
        @DefaultValue("16") int comparisonQueueCapacity) {

    /** Validated here, so the boot check's drain arithmetic never divides by zero threads. */
    public AiExecutionProperties {
        Assert.isTrue(analysisThreads > 0 && comparisonThreads > 0, "AI executor thread count must be positive");
        Assert.isTrue(analysisQueueCapacity >= 0 && comparisonQueueCapacity >= 0,
                "AI executor queue capacity must not be negative");
    }
}
