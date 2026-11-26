package com.job_search.fair_path.services;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.job_search.fair_path.config.AiServiceProperties;

/** Shared async dispatch and original Analysis retry policy, with isolated feature pools. */
@Component
public class AiRequestExecution {
    private static final Logger log = LoggerFactory.getLogger(AiRequestExecution.class);

    /** The original call plus the one automatic retry; the sweep's boot check uses the same count. */
    public static final int CALLS_PER_REQUEST = 2;

    private final AiServiceProperties properties;
    public AiRequestExecution(AiServiceProperties properties) { this.properties = properties; }

    @Async("analysisExecutor")
    public void executeAnalysis(Runnable task) { task.run(); }

    @Async("comparisonExecutor")
    public void executeComparison(Runnable task) { task.run(); }

    public <T> T call(UUID correlationId, Supplier<T> request) {
        for (int call = 1;; call++) {
            try {
                return request.get();
            } catch (RuntimeException failure) {
                if (call >= CALLS_PER_REQUEST || !AiServiceErrors.isUnreachable(failure)) throw failure;
                Duration delay = properties.retryDelay();
                log.warn("AI service unreachable, retrying once in {} correlation_id={}", delay, correlationId);
                try {
                    if (!delay.isZero()) Thread.sleep(delay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
    }
}
