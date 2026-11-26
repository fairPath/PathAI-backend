package com.job_search.fair_path.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Shared maintenance scheduler and stranded-work sweep (fairpath.maintenance.*). */
@ConfigurationProperties("fairpath.maintenance")
public record MaintenanceProperties(
        /** One thread per scheduled job: the sweep and account deletion. */
        @DefaultValue("2") int schedulerThreads,
        /** Fixed delay between sweeps. */
        @DefaultValue("1m") Duration sweepInterval) {
}
