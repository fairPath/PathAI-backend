package com.job_search.fair_path.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.job_search.fair_path.config.ComparisonSchedulerConfig;

class ComparisonSchedulingTest {
    @Test
    void maintenanceRunsWhileComparisonIsBlockedInInference() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(ComparisonSchedulerConfig.class)) {
            var queue = mock(JobComparisonQueue.class);
            var client = mock(JobComparisonClient.class);
            var task = new JobComparisonQueue.Task(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "job", "active.pdf", "Java", 1, UUID.randomUUID(), Instant.now());
            when(queue.claim()).thenReturn(Optional.of(task));
            var inAi = new CountDownLatch(1);
            var releaseAi = new CountDownLatch(1);
            var maintenanceRan = new CountDownLatch(1);
            when(client.compare(task)).thenAnswer(invocation -> {
                inAi.countDown();
                if (!releaseAi.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release AI");
                return new JobComparisonClient.Result(80, List.of(), List.of(), "", List.of());
            });
            String schedulerName = JobComparisonWorker.class.getMethod("processNext")
                    .getAnnotation(Scheduled.class).scheduler();
            var comparisonScheduler = context.getBean(schedulerName, ThreadPoolTaskScheduler.class);
            var maintenanceScheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            try {
                comparisonScheduler.schedule(new JobComparisonWorker(queue, client)::processNext, Instant.now());
                assertTrue(inAi.await(2, TimeUnit.SECONDS));
                maintenanceScheduler.schedule(maintenanceRan::countDown, Instant.now());
                assertTrue(maintenanceRan.await(1, TimeUnit.SECONDS),
                        "Blocked AI work must not delay scheduled recovery");
            } finally {
                releaseAi.countDown();
            }
        }
    }
}
