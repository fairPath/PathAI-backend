package com.job_search.fair_path.services;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.job_search.fair_path.config.AiServiceProperties;

/**
 * Runs every SweepTarget, each in its own transaction, so one failing target
 * neither stops nor rolls back the others. Idempotent, threshold-based and
 * clocked by the injected Clock. Logs the target name and count only, never rows.
 *
 * Refuses to be built when a target's processing threshold does not outlast the
 * slowest legitimate AI call, so a running call is never swept.
 */
@Component
public class StaleWorkSweeper {
    private static final Logger log = LoggerFactory.getLogger(StaleWorkSweeper.class);

    private final List<SweepTarget> targets;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public StaleWorkSweeper(List<SweepTarget> targets, PlatformTransactionManager transactions, Clock clock,
            AiServiceProperties ai) {
        this.targets = List.copyOf(targets);
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        // The original call plus the one retry (the same constant AiRequestExecution.call uses), and the wait between.
        Duration worstCase = ai.readTimeout().multipliedBy(AiRequestExecution.CALLS_PER_REQUEST).plus(ai.retryDelay());
        for (SweepTarget target : this.targets) {
            if (target.pendingStaleAfter().isZero() || target.pendingStaleAfter().isNegative()) {
                throw new IllegalStateException(target.name() + " pending-stale-after must be positive");
            }
            if (target.processingStaleAfter().compareTo(worstCase) <= 0) {
                throw new IllegalStateException(target.name() + " processing-stale-after ("
                        + target.processingStaleAfter() + ") must exceed " + AiRequestExecution.CALLS_PER_REQUEST
                        + " x read-timeout + retry-delay (" + worstCase + ")");
            }
        }
    }

    public void sweep() {
        var now = clock.instant();
        for (SweepTarget target : targets) {
            try {
                Integer swept = transaction.execute(status -> target.sweep(now));
                if (swept != null && swept > 0) {
                    log.warn("Sweep timed out stale work target={} count={}", target.name(), swept);
                }
            } catch (RuntimeException failure) {
                // Class name only: exception messages can carry row data.
                log.error("Sweep failed target={} error={}", target.name(), failure.getClass().getSimpleName());
            }
        }
    }
}
