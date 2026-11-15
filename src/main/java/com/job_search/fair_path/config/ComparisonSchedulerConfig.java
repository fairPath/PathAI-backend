package com.job_search.fair_path.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ComparisonSchedulerConfig {
    @Bean("taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("maintenance-");
        return scheduler;
    }
    @Bean("comparisonScheduler")
    ThreadPoolTaskScheduler comparisonScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("comparison-");
        return scheduler;
    }
}
