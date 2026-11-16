package com.job_search.fair_path.services;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.job_search.fair_path.config.AiServiceProperties;

class ComparisonSchedulingTest {
    @Configuration
    @EnableAsync
    @Import(TaskExecutionAutoConfiguration.class)
    static class Config {
        @Bean AiRequestExecution execution() {
            return new AiRequestExecution(new AiServiceProperties("http://unused", "token",
                Duration.ofSeconds(5), Duration.ofSeconds(120), Duration.ZERO, 8192));
        }
    }

    @Test
    void bothFeaturesUseOriginalSpringPoolAndCanRunConcurrently() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var pool = context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            assertEquals(8, pool.getCorePoolSize()); // Spring Boot's original default.
            var execution = context.getBean(AiRequestExecution.class);
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            Runnable aiWork = () -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            };
            try {
                execution.execute(aiWork); // Analysis
                execution.execute(aiWork); // Comparison
                assertTrue(entered.await(2, TimeUnit.SECONDS), "One blocked AI request must not serialize the other");
                assertEquals(2, pool.getActiveCount());
            } finally { release.countDown(); }
        }
    }
}
