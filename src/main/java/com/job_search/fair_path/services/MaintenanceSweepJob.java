package com.job_search.fair_path.services;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.job_search.fair_path.config.MaintenanceConfig;

/** The sweep's schedule: once at startup, then on a fixed delay. Off in tests, which call the sweep directly. */
@Component
@ConditionalOnProperty(name = "fairpath.maintenance.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceSweepJob {
    private final StaleWorkSweeper sweeper;

    public MaintenanceSweepJob(StaleWorkSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${fairpath.maintenance.sweep-interval:1m}",
            scheduler = MaintenanceConfig.SCHEDULER)
    public void run() {
        sweeper.sweep();
    }
}
