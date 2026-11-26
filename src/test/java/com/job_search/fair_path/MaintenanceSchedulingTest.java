package com.job_search.fair_path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.job_search.fair_path.config.AccountDeletionConfiguration;
import com.job_search.fair_path.config.MaintenanceConfig;
import com.job_search.fair_path.services.AccountDeletionWorker;
import com.job_search.fair_path.services.MaintenanceSweepJob;
import com.job_search.fair_path.services.StaleWorkSweeper;

/**
 * The only tests that run real scheduling. A small context with just the scheduler,
 * the sweep job and the deletion poll (workers mocked): no Spring Boot context, no database.
 */
class MaintenanceSchedulingTest {

    @Configuration
    @EnableScheduling
    @Import({ MaintenanceConfig.class, MaintenanceSweepJob.class, AccountDeletionConfiguration.class })
    static class Config {
    }

    private AnnotationConfigApplicationContext context(StaleWorkSweeper sweeper, AccountDeletionWorker worker,
            String... properties) {
        var context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("fairpath.maintenance.sweep-interval=20ms",
                "fairpath.account-deletion.scheduling-enabled=true",
                "fairpath.account-deletion.poll-interval=20ms").and(properties).applyTo(context);
        context.registerBean(StaleWorkSweeper.class, () -> sweeper);
        context.registerBean(AccountDeletionWorker.class, () -> worker);
        context.register(Config.class);
        context.refresh();
        return context;
    }

    @Test
    void theSweepRunsAtStartupAndRepeatsAndDeletionRunsOnTheSharedSchedulerWithItsOwnThread() {
        var sweeper = mock(StaleWorkSweeper.class);
        var worker = mock(AccountDeletionWorker.class);
        var sweepThreads = new CopyOnWriteArrayList<String>();
        var deletionThreads = new CopyOnWriteArrayList<String>();
        doAnswer(call -> sweepThreads.add(Thread.currentThread().getName())).when(sweeper).sweep();
        doAnswer(call -> deletionThreads.add(Thread.currentThread().getName())).when(worker).run();

        try (var context = context(sweeper, worker)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> sweepThreads.size() >= 2 && deletionThreads.size() >= 2);
        }
        assertThat(sweepThreads).allMatch(name -> name.startsWith("maintenance-"));
        assertThat(deletionThreads).allMatch(name -> name.startsWith("maintenance-"));
    }

    @Test
    void aDeletionBlockedOnS3DoesNotDelayTheSweep() throws Exception {
        var sweeper = mock(StaleWorkSweeper.class);
        var worker = mock(AccountDeletionWorker.class);
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            blocked.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(worker).run();

        try (var context = context(sweeper, worker)) {
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            // The deletion thread is held; the sweep keeps its own cadence.
            verify(sweeper, org.mockito.Mockito.timeout(5000).atLeast(3)).sweep();
            release.countDown();
        }
    }

    @Test
    void theSchedulerThreadCountMustBePositive() {
        assertThatThrownBy(() -> context(mock(StaleWorkSweeper.class), mock(AccountDeletionWorker.class),
                "fairpath.maintenance.scheduler-threads=0")).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
