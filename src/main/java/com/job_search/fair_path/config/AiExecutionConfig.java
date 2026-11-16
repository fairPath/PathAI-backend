package com.job_search.fair_path.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;

/** Separate capacity for each feature, with the same shared execution/retry implementation. */
@Configuration
public class AiExecutionConfig {
    @Bean("analysisExecutor")
    ThreadPoolTaskExecutor analysisExecutor(@Value("${fairpath.ai-execution.analysis-threads:8}") int threads) {
        return executor(threads, "analysis-");
    }

    @Bean("comparisonExecutor")
    ThreadPoolTaskExecutor comparisonExecutor(@Value("${fairpath.ai-execution.comparison-threads:8}") int threads) {
        return executor(threads, "comparison-");
    }

    private ThreadPoolTaskExecutor executor(int threads, String prefix) {
        Assert.isTrue(threads > 0, "AI executor thread count must be positive");
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setThreadNamePrefix(prefix);
        // Retain the original queue behavior; only isolate worker capacity here.
        return executor;
    }
}
