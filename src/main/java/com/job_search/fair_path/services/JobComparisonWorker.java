package com.job_search.fair_path.services;

import com.job_search.fair_path.exceptions.ComparisonFailure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fairpath.comparison-worker.enabled", havingValue = "true", matchIfMissing = true)
public class JobComparisonWorker {
    private static final Logger logger = LoggerFactory.getLogger(JobComparisonWorker.class);
    private final JobComparisonQueue queue;
    private final JobComparisonClient client;

    public JobComparisonWorker(JobComparisonQueue queue, JobComparisonClient client) {
        this.queue = queue;
        this.client = client;
    }

    @Scheduled(scheduler = "comparisonScheduler", fixedDelayString = "${fairpath.comparison-worker.poll-ms:1000}",
            initialDelayString = "${fairpath.comparison-worker.initial-delay-ms:5000}")
    public void processNext() {
        try {
            var task = queue.claim();
            if (task.isPresent()) process(task.get());
        } catch (Exception error) {
            logger.error("Comparison queue unavailable error_type={}", error.getClass().getSimpleName());
        }
    }

    private void process(JobComparisonQueue.Task task) {
        try {
            if (task.resumeId() == null || task.s3Key() == null || task.resumeUpdatedAt() == null)
                throw new ComparisonFailure("RESUME_REQUIRED", false);
            if (!queue.complete(task, client.compare(task)))
                queue.retryOrFail(task, new ComparisonFailure("RESUME_CHANGED", true));
        } catch (ComparisonFailure failure) {
            logger.warn("Comparison failed correlation_id={} attempt={} error_code={}",
                    task.requestId(), task.attempt(), failure.errorCode());
            queue.retryOrFail(task, failure);
        } catch (Exception error) {
            logger.error("Comparison failed correlation_id={} error_type={}",
                    task.requestId(), error.getClass().getSimpleName());
            queue.retryOrFail(task, new ComparisonFailure("INTERNAL_ERROR", AiServiceErrors.isTransientDatabase(error)));
        }
    }
}
