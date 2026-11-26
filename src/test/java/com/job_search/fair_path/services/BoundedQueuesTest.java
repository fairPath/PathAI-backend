package com.job_search.fair_path.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.job_search.fair_path.support.AnalysisTestSupport;

/**
 * A full pool queue fails the committed row at once with AI_BUSY, for both features. The real pools are
 * saturated with latches (8 threads, 16 queued by default): nothing waits, and no AI call is made.
 */
class BoundedQueuesTest extends AnalysisTestSupport {

    @Autowired AiRequestExecution execution;
    @Autowired @Qualifier("analysisExecutor") ThreadPoolTaskExecutor analysisExecutor;
    @Autowired @Qualifier("comparisonExecutor") ThreadPoolTaskExecutor comparisonExecutor;
    @Autowired @Qualifier("maintenanceScheduler") ThreadPoolTaskScheduler maintenanceScheduler;
    @Autowired StaleWorkSweeper sweeper;
    @Autowired JdbcTemplate jdbc;

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger queuedRan = new AtomicInteger();

    @AfterEach
    void releasePools() {
        release.countDown();
    }

    /** Every thread blocked and the whole queue filled, so the next submission is rejected. */
    private int saturate(ThreadPoolTaskExecutor pool, Consumer<Runnable> submit) throws Exception {
        int threads = pool.getCorePoolSize();
        int capacity = pool.getQueueCapacity();
        var started = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            submit.accept(() -> {
                started.countDown();
                await();
            });
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < capacity; i++) {
            submit.accept(queuedRan::incrementAndGet);
        }
        assertThat(pool.getQueueSize()).isEqualTo(capacity);
        return capacity;
    }

    private void await() {
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitQueuedWorkRan(int expected) throws Exception {
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queuedRan.get() < expected && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(queuedRan.get()).isEqualTo(expected);
    }

    private void saveJob() throws Exception {
        mockMvc.perform(post("/saved-jobs/prepare").header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"job\",\"title\":\"Engineer\",\"jobDescription\":\"Java and Go\"}"))
                .andExpect(status().isOk());
    }

    private Map<String, Object> jobRow() {
        return jdbc.queryForMap("SELECT comparison_status, comparison_error_code, comparison_retryable,"
                + " comparison_lease_token FROM saved_jobs WHERE job_id = 'job'");
    }

    @Test
    void aFullAnalysisQueueFailsTheRowWithAiBusyAndTheQueuedWorkStillRuns() throws Exception {
        int queued = saturate(analysisExecutor, execution::executeAnalysis);

        // The request itself still returns normally: the listener must not throw after commit.
        mockMvc.perform(post("/resumes/analyze").header("Authorization", bearerToken))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/resumes/analysis").header("Authorization", bearerToken))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error_code").value("AI_BUSY"))
                .andExpect(jsonPath("$.retryable").value(true));
        assertThat(AnalysisErrorCodes.isRetryable("AI_BUSY")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_record", Integer.class)).isZero();
        aiService.verify(0, com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));

        awaitQueuedWorkRan(queued);
    }

    @Test
    void aFullComparisonQueueFailsTheRowWithAiBusyAndTheQueuedWorkStillRuns() throws Exception {
        int queued = saturate(comparisonExecutor, execution::executeComparison);
        doCallRealMethod().when(comparisonWorker).onComparisonRequested(any());
        saveJob();

        mockMvc.perform(post("/saved-jobs/compare").header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"job\"}"))
                .andExpect(status().isAccepted());

        assertThat(jobRow()).containsEntry("comparison_status", "FAILED")
                .containsEntry("comparison_error_code", "AI_BUSY").containsEntry("comparison_retryable", true)
                .containsEntry("comparison_lease_token", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_record", Integer.class)).isZero();
        aiService.verify(0, com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));

        awaitQueuedWorkRan(queued);
    }

    @Test
    void theSweepStillRunsOnItsOwnSchedulerWhileBothPoolsAreSaturated() throws Exception {
        saturate(analysisExecutor, execution::executeAnalysis);
        saturate(comparisonExecutor, execution::executeComparison);
        UUID analysis = UUID.randomUUID();
        jdbc.update("INSERT INTO analysis (id, user_id, resume_id, status, created_at) VALUES (?, ?, ?, 'PENDING', ?)",
                analysis, user.getId(), resume.getId(), Timestamp.from(clock.instant()));
        UUID job = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO saved_jobs (id, user_id, job_id, resume_id, comparison_status, updated_at)
                VALUES (?, ?, 'job', ?, 'PENDING', ?)
                """, job, user.getId(), resume.getId(), Timestamp.from(clock.instant()));

        clock.advance(Duration.ofMinutes(11));
        maintenanceScheduler.submit(sweeper::sweep).get(5, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT error_code FROM analysis WHERE id = ?", String.class, analysis))
                .isEqualTo("ANALYSIS_TIMED_OUT");
        assertThat(jdbc.queryForObject("SELECT comparison_error_code FROM saved_jobs WHERE id = ?", String.class, job))
                .isEqualTo("COMPARISON_TIMED_OUT");
    }
}
