package com.job_search.fair_path.services;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.job_search.fair_path.config.AiExecutionConfig;
import com.job_search.fair_path.config.AiServiceProperties;

class ComparisonSchedulingTest {
    @Configuration
    @EnableAsync
    @Import(AiExecutionConfig.class)
    static class Config {
        @Bean AiRequestExecution execution() {
            return new AiRequestExecution(new AiServiceProperties("http://unused", "token",
                Duration.ofSeconds(5), Duration.ofSeconds(120), Duration.ZERO, 8192));
        }
    }

    @Test
    void eightBlockedComparisonsCannotConsumeAnalysisCapacity() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var analysis = context.getBean("analysisExecutor", ThreadPoolTaskExecutor.class);
            var comparison = context.getBean("comparisonExecutor", ThreadPoolTaskExecutor.class);
            assertEquals(8, analysis.getCorePoolSize());
            assertEquals(8, analysis.getMaxPoolSize());
            assertEquals(8, comparison.getCorePoolSize());
            assertEquals(8, comparison.getMaxPoolSize());
            var execution = context.getBean(AiRequestExecution.class);
            var comparisonsStarted = new CountDownLatch(8);
            var analysesStarted = new CountDownLatch(8);
            var release = new CountDownLatch(1);
            Set<String> names = ConcurrentHashMap.newKeySet();
            try {
                for (int i = 0; i < 8; i++) execution.executeComparison(() -> block(comparisonsStarted, release, names));
                assertTrue(comparisonsStarted.await(5, TimeUnit.SECONDS));
                execution.executeComparison(() -> {}); // Ninth comparison must queue.
                assertEquals(1, comparison.getQueueSize());
                for (int i = 0; i < 8; i++) execution.executeAnalysis(() -> block(analysesStarted, release, names));
                assertTrue(analysesStarted.await(5, TimeUnit.SECONDS), "Comparison saturation must not delay Analysis");
                execution.executeAnalysis(() -> {}); // Ninth Analysis must queue in its own pool.
                assertEquals(1, analysis.getQueueSize());
                assertEquals(8, comparison.getActiveCount());
                assertEquals(8, analysis.getActiveCount());
                assertEquals(8, names.stream().filter(name -> name.startsWith("comparison-")).count());
                assertEquals(8, names.stream().filter(name -> name.startsWith("analysis-")).count());
            } finally { release.countDown(); }
        }
    }

    private static void block(CountDownLatch started, CountDownLatch release, Set<String> names) {
        names.add(Thread.currentThread().getName());
        started.countDown();
        try { release.await(15, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    @Test
    void poolSizesAreIndependentlyConfigurable() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("fairpath.ai-execution.analysis-threads=2",
                "fairpath.ai-execution.comparison-threads=3").applyTo(context);
            context.register(Config.class);
            context.refresh();
            assertEquals(2, context.getBean("analysisExecutor", ThreadPoolTaskExecutor.class).getMaxPoolSize());
            assertEquals(3, context.getBean("comparisonExecutor", ThreadPoolTaskExecutor.class).getMaxPoolSize());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"analysis", "comparison"})
    void nonpositivePoolSizeFailsStartup(String feature) {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("fairpath.ai-execution." + feature + "-threads=0").applyTo(context);
            context.register(Config.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
        }
    }

    @Test
    void aFullQueueRejectsTheNextSubmissionAndTheQueuedWorkStillRuns() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("fairpath.ai-execution.analysis-threads=1",
                "fairpath.ai-execution.analysis-queue-capacity=2").applyTo(context);
            context.register(Config.class);
            context.refresh();
            var execution = context.getBean(AiRequestExecution.class);
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var ran = new java.util.concurrent.atomic.AtomicInteger();
            execution.executeAnalysis(() -> block(started, release, ConcurrentHashMap.newKeySet()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                execution.executeAnalysis(ran::incrementAndGet);
                execution.executeAnalysis(ran::incrementAndGet);
                assertThrows(org.springframework.core.task.TaskRejectedException.class,
                    () -> execution.executeAnalysis(ran::incrementAndGet));
                // The other pool has its own queue (default 16).
                execution.executeComparison(() -> {});
            } finally { release.countDown(); }
            var analysis = context.getBean("analysisExecutor", ThreadPoolTaskExecutor.class);
            analysis.getThreadPoolExecutor().shutdown(); // lets the queued work finish
            assertTrue(analysis.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, ran.get());
        }
    }

    @Test
    void queueCapacityDefaultsToSixteenAndZeroMeansNoWaiting() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("fairpath.ai-execution.comparison-queue-capacity=0").applyTo(context);
            context.register(Config.class);
            context.refresh();
            assertEquals(16, context.getBean("analysisExecutor", ThreadPoolTaskExecutor.class).getQueueCapacity());
            assertEquals(0, context.getBean("comparisonExecutor", ThreadPoolTaskExecutor.class).getQueueCapacity());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"analysis", "comparison"})
    void negativeQueueCapacityFailsStartup(String feature) {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("fairpath.ai-execution." + feature + "-queue-capacity=-1").applyTo(context);
            context.register(Config.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
        }
    }
}
