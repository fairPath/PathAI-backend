package com.job_search.fair_path.services;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.job_search.fair_path.config.ComparisonProperties;

/**
 * PENDING comparisons whose lease expired (the lease is the processing
 * threshold), and PENDING ones with no lease that are older than the pending
 * threshold, measured from updated_at: their after-commit event was lost.
 */
@Component
public class ComparisonSweepTarget implements SweepTarget {
    static final String COMPARISON_TIMED_OUT = "COMPARISON_TIMED_OUT";

    private final JdbcTemplate jdbc;
    private final ComparisonProperties properties;

    public ComparisonSweepTarget(JdbcTemplate jdbc, ComparisonProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override public String name() { return "comparison"; }
    @Override public Duration pendingStaleAfter() { return properties.pendingStaleAfter(); }
    @Override public Duration processingStaleAfter() { return properties.processingStaleAfter(); }

    @Override
    public int sweep(Instant now) {
        return jdbc.queryForList("""
                UPDATE saved_jobs SET comparison_status = 'FAILED', comparison_error_code = ?,
                    comparison_retryable = TRUE, comparison_lease_token = NULL,
                    comparison_lease_until = NULL, updated_at = ?
                WHERE comparison_status = 'PENDING'
                    AND ((comparison_lease_until IS NOT NULL AND comparison_lease_until <= ?)
                        OR (comparison_lease_until IS NULL AND updated_at <= ?))
                RETURNING id
                """, COMPARISON_TIMED_OUT, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now.minus(pendingStaleAfter()))).size();
    }
}
