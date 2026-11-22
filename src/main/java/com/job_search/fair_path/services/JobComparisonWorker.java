package com.job_search.fair_path.services;

import com.job_search.fair_path.exceptions.ComparisonFailure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fairpath.comparison-worker.enabled", havingValue = "true", matchIfMissing = true)
public class JobComparisonWorker {
    private static final Logger logger = LoggerFactory.getLogger(JobComparisonWorker.class);
    private final JobComparisonQueue queue;
    private final JobComparisonClient client;
    private final AiRequestExecution execution;

    public JobComparisonWorker(JobComparisonQueue queue, JobComparisonClient client, AiRequestExecution execution) {
        this.queue = queue;
        this.client = client;
        this.execution = execution;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onComparisonRequested(ComparisonRequestedEvent event) {
        execution.executeComparison(() -> processNext(event.requestId()));
    }

    public void processNext() { processNext(null); }

    private void processNext(UUID requestId) {
        try {
            var task = requestId == null ? queue.claim() : queue.claim(requestId);
            if (task.isPresent()) process(task.get());
        } catch (Exception error) {
            logger.error("Comparison queue unavailable error_type={}", error.getClass().getSimpleName());
        }
    }

    private void process(JobComparisonQueue.Task task) {
        try {
            if (task.resumeId() == null || task.s3Key() == null || task.resumeUpdatedAt() == null)
                throw new ComparisonFailure("RESUME_REQUIRED", false);
            var result = execution.call(task.requestId(), () -> queue.belongsToActiveAccount(task), () -> client.compare(task));
            if (result == null) return; // Same as Analysis: uncorrelated success is left for recovery.
            if (!queue.complete(task, result))
                queue.fail(task, new ComparisonFailure("RESUME_CHANGED", true));
        } catch (ComparisonFailure failure) {
            if (AiServiceErrors.hasMismatchedCorrelation(failure, task.requestId())) return;
            logger.warn("Comparison failed correlation_id={} attempt={} error_code={}",
                    task.requestId(), task.attempt(), failure.errorCode());
            queue.fail(task, failure);
        } catch (Exception error) {
            if (AiServiceErrors.hasMismatchedCorrelation(error, task.requestId())) return;
            logger.error("Comparison failed correlation_id={} error_type={}",
                    task.requestId(), error.getClass().getSimpleName());
            queue.fail(task, AiServiceErrors.comparisonFailure(error, task.requestId()));
        }
    }
}
