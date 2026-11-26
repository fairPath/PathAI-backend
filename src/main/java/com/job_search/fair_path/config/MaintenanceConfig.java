package com.job_search.fair_path.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.Assert;

/**
 * The scheduler every @Scheduled job names. It is neither AI pool, so it still
 * runs when both are saturated, and it has one thread per job so a slow job
 * (a deletion blocked on S3) never delays another (the sweep).
 */
@Configuration
@EnableConfigurationProperties({ MaintenanceProperties.class, ComparisonProperties.class })
public class MaintenanceConfig {

    public static final String SCHEDULER = "maintenanceScheduler";

    @Bean(SCHEDULER)
    ThreadPoolTaskScheduler maintenanceScheduler(MaintenanceProperties properties) {
        Assert.isTrue(properties.schedulerThreads() > 0, "fairpath.maintenance.scheduler-threads must be positive");
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.schedulerThreads());
        scheduler.setThreadNamePrefix("maintenance-");
        return scheduler;
    }
}
