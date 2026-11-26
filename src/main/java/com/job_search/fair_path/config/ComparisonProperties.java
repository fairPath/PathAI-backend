package com.job_search.fair_path.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Job comparison thresholds (fairpath.comparison.*), the same two rules as Analysis. */
@ConfigurationProperties("fairpath.comparison")
public record ComparisonProperties(
        /** A PENDING row with no lease this old (from updated_at) lost its after-commit event. */
        @DefaultValue("10m") Duration pendingStaleAfter,
        /** The lease length: a claimed row past it is stuck. */
        @DefaultValue("300s") Duration processingStaleAfter) {
}
