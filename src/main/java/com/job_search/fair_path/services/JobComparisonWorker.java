package com.job_search.fair_path.services;

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

    @Scheduled(fixedDelayString = "${fairpath.comparison-worker.poll-ms:1000}", initialDelayString = "5000")
    public void processNext() {
        try {
            var task = queue.claim();
            if (task.isEmpty()) return;
            process(task.get());
        } catch (Exception error) {
            // A failed database update leaves the lease intact for recovery after expiry.
            logger.error("Comparison queue unavailable; will retry on next poll", error);
        }
    }

    private void process(JobComparisonQueue.Task task) {
        try {
            queue.complete(task, client.compare(task));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            // Leave the claim durable; another process can recover it after the lease expires.
        } catch (Exception error) {
            logger.warn("Comparison attempt failed job_id={} attempt={} error_type={}",
                    task.jobId(), task.attempt(), error.getClass().getSimpleName());
            queue.retryOrFail(task);
        }
    }
}
