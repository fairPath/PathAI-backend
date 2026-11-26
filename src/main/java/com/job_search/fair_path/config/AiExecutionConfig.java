package com.job_search.fair_path.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;

/**
 * Separate bounded capacity for each feature, with the same shared execution/retry implementation.
 * A full queue rejects the submission (AbortPolicy, surfaced as Spring's TaskRejectedException);
 * each feature's listener turns that into an AI_BUSY failure.
 */
@Configuration
@EnableConfigurationProperties(AiExecutionProperties.class)
public class AiExecutionConfig {
    @Bean("analysisExecutor")
    ThreadPoolTaskExecutor analysisExecutor(AiExecutionProperties properties) {
        return executor(properties.analysisThreads(), properties.analysisQueueCapacity(), "analysis-");
    }

    @Bean("comparisonExecutor")
    ThreadPoolTaskExecutor comparisonExecutor(AiExecutionProperties properties) {
        return executor(properties.comparisonThreads(), properties.comparisonQueueCapacity(), "comparison-");
    }

    private ThreadPoolTaskExecutor executor(int threads, int queueCapacity, String prefix) {
        Assert.isTrue(threads > 0, "AI executor thread count must be positive");
        Assert.isTrue(queueCapacity >= 0, "AI executor queue capacity must not be negative");
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix(prefix);
        return executor;
    }
}
