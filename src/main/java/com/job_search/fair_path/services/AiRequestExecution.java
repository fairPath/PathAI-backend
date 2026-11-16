package com.job_search.fair_path.services;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.job_search.fair_path.config.AiServiceProperties;

/** Analysis's original async executor and one-retry policy, shared by every AI feature. */
@Component
public class AiRequestExecution {
    private static final Logger log = LoggerFactory.getLogger(AiRequestExecution.class);
    private final AiServiceProperties properties;
    public AiRequestExecution(AiServiceProperties properties) { this.properties = properties; }

    // Use Spring's original default @Async executor, never a scheduled-maintenance thread.
    @Async
    public void execute(Runnable task) { task.run(); }

    public <T> T call(UUID correlationId, Supplier<T> request) {
        try {
            return request.get();
        } catch (RuntimeException first) {
            if (!AiServiceErrors.isUnreachable(first)) throw first;
            Duration delay = properties.retryDelay();
            log.warn("AI service unreachable, retrying once in {} correlation_id={}", delay, correlationId);
            try {
                if (!delay.isZero()) Thread.sleep(delay.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return request.get();
        }
    }
}
