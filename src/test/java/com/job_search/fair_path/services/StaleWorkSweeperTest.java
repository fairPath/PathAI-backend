package com.job_search.fair_path.services;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.job_search.fair_path.config.AiServiceProperties;
import com.job_search.fair_path.dataTransferObject.analysis.ScoresDTO;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.AnalysisRepository;
import com.job_search.fair_path.support.AnalysisTestSupport;

/**
 * The stranded-work sweep against real Postgres. Time is the test clock: nothing waits.
 * Defaults under test: Analysis pending 10 min / processing 5 min, comparison pending 10 min / lease 5 min.
 */
class StaleWorkSweeperTest extends AnalysisTestSupport {

    @Autowired StaleWorkSweeper sweeper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AnalysisRepository analyses;
    @Autowired AiServiceProperties aiProperties;
    @Autowired AnalysisSweepTarget analysisTarget;
    @Autowired ComparisonSweepTarget comparisonTarget;

    private User otherUser() {
        String unique = UUID.randomUUID().toString();
        var other = new User("Other", "User", "other-" + unique, unique + "@example.invalid", "not-a-real-hash");
        other.setEnabled(true);
        return userRepository.save(other);
    }

    private UUID analysis(User owner, String status, Instant createdAt, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis (id, user_id, resume_id, status, created_at, started_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, owner.getId(), resume.getId(), status, Timestamp.from(createdAt),
                startedAt == null ? null : Timestamp.from(startedAt));
        return id;
    }

    private UUID savedJob(String status, Instant updatedAt, Instant leaseUntil) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO saved_jobs (id, user_id, job_id, resume_id, comparison_status, updated_at,
                    comparison_lease_until, comparison_lease_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, user.getId(), "synthetic-job-" + id, resume.getId(), status, Timestamp.from(updatedAt),
                leaseUntil == null ? null : Timestamp.from(leaseUntil), leaseUntil == null ? null : UUID.randomUUID());
        return id;
    }

    private Map<String, Object> analysisRow(UUID id) {
        return jdbc.queryForMap("SELECT status, error_code FROM analysis WHERE id = ?", id);
    }

    private Map<String, Object> jobRow(UUID id) {
        return jdbc.queryForMap("SELECT comparison_status, comparison_error_code, comparison_retryable"
                + " FROM saved_jobs WHERE id = ?", id);
    }

    @Test
    void pendingAnalysisIsTimedOutOnlyPastItsThresholdFromCreatedAt() {
        UUID id = analysis(user, "PENDING", clock.instant(), null);

        clock.advance(Duration.ofMinutes(9));
        sweeper.sweep();
        assertThat(analysisRow(id)).containsEntry("status", "PENDING");

        clock.advance(Duration.ofMinutes(2));
        sweeper.sweep();
        assertThat(analysisRow(id)).containsEntry("status", "FAILED").containsEntry("error_code", "ANALYSIS_TIMED_OUT");
    }

    @Test
    void processingAnalysisIsTimedOutOnlyPastItsThresholdFromStartedAt() {
        // Created long ago but started just now: the processing clock runs from started_at.
        UUID id = analysis(user, "PROCESSING", clock.instant().minus(Duration.ofMinutes(8)), clock.instant());

        clock.advance(Duration.ofMinutes(4));
        sweeper.sweep();
        assertThat(analysisRow(id)).containsEntry("status", "PROCESSING");

        clock.advance(Duration.ofMinutes(2));
        sweeper.sweep();
        assertThat(analysisRow(id)).containsEntry("status", "FAILED").containsEntry("error_code", "ANALYSIS_TIMED_OUT");
    }

    @Test
    void startupRunLeavesFreshRowsAloneAndTheSweepIsIdempotent() {
        UUID pending = analysis(user, "PENDING", clock.instant(), null);
        UUID processing = analysis(otherUser(), "PROCESSING", clock.instant(), clock.instant());
        UUID job = savedJob("PENDING", clock.instant(), null);
        UUID leased = savedJob("PENDING", clock.instant(), clock.instant().plus(Duration.ofMinutes(5)));

        sweeper.sweep();
        sweeper.sweep();

        assertThat(analysisRow(pending)).containsEntry("status", "PENDING");
        assertThat(analysisRow(processing)).containsEntry("status", "PROCESSING");
        assertThat(jobRow(job)).containsEntry("comparison_status", "PENDING");
        assertThat(jobRow(leased)).containsEntry("comparison_status", "PENDING");
    }

    @Test
    void aLateResultAfterTheSweepIsDiscarded() {
        UUID id = analysis(user, "PROCESSING", clock.instant(), clock.instant());
        clock.advance(Duration.ofMinutes(6));
        sweeper.sweep();

        assertThat(analyses.complete(id, new ScoresDTO(1, 2, 3, 4), "[]", "synthetic", "0.1.0")).isZero();
        assertThat(analyses.failProcessing(id, "INTERNAL_ERROR")).isZero();
        assertThat(analysisRow(id)).containsEntry("status", "FAILED").containsEntry("error_code", "ANALYSIS_TIMED_OUT");
        assertThat(jdbc.queryForObject("SELECT score_overall FROM analysis WHERE id = ?", Integer.class, id)).isNull();
    }

    @Test
    void comparisonWithAnExpiredLeaseAndOneWhoseEventWasLostAreTimedOut() {
        UUID expired = savedJob("PENDING", clock.instant(), clock.instant().plus(Duration.ofMinutes(5)));
        UUID lost = savedJob("PENDING", clock.instant(), null);
        UUID done = savedJob("COMPLETED", clock.instant().minus(Duration.ofHours(1)), null);

        clock.advance(Duration.ofMinutes(6));
        sweeper.sweep();
        assertThat(jobRow(expired)).containsEntry("comparison_status", "FAILED")
                .containsEntry("comparison_error_code", "COMPARISON_TIMED_OUT").containsEntry("comparison_retryable", true);
        assertThat(jobRow(lost)).containsEntry("comparison_status", "PENDING");

        clock.advance(Duration.ofMinutes(5));
        sweeper.sweep();
        assertThat(jobRow(lost)).containsEntry("comparison_status", "FAILED")
                .containsEntry("comparison_error_code", "COMPARISON_TIMED_OUT");
        assertThat(jobRow(done)).containsEntry("comparison_status", "COMPLETED");
    }

    @Test
    void aTargetFailingAfterItsUpdateIsRolledBackWhileOtherTargetsStillCommit() {
        UUID id = analysis(user, "PENDING", clock.instant(), null);
        UUID lost = savedJob("PENDING", clock.instant(), null);
        SweepTarget failing = new SweepTarget() {
            public String name() { return "failing"; }
            public Duration pendingStaleAfter() { return Duration.ofMinutes(10); }
            public Duration processingStaleAfter() { return Duration.ofMinutes(5); }
            public int poolThreads() { return 8; }
            public int poolQueueCapacity() { return 16; }
            public int sweep(Instant now) {
                jdbc.update("UPDATE analysis SET status = 'FAILED', error_code = 'PARTIAL' WHERE id = ?", id);
                throw new IllegalStateException("synthetic failure after the update");
            }
        };
        var runner = new StaleWorkSweeper(List.of(failing, comparisonTarget), transactions, clock, aiProperties);

        clock.advance(Duration.ofMinutes(11));
        runner.sweep();

        assertThat(analysisRow(id)).containsEntry("status", "PENDING").containsEntry("error_code", null);
        assertThat(jobRow(lost)).containsEntry("comparison_status", "FAILED");
    }

    @Test
    void theRunningBootCheckRefusesAProcessingThresholdThatDoesNotOutlastTheCalls() {
        var ai = new AiServiceProperties("http://unused", "token", Duration.ofSeconds(5),
                Duration.ofSeconds(120), Duration.ofSeconds(5), 8192);
        // 2 x 120 s + 5 s = 245 s
        assertThat(AiRequestExecution.CALLS_PER_REQUEST).isEqualTo(2);
        assertThatThrownBy(() -> new StaleWorkSweeper(List.of(target(Duration.ofSeconds(245))), transactions, clock, ai))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("processing-stale-after");
        new StaleWorkSweeper(List.of(target(Duration.ofSeconds(246))), transactions, clock, ai);
        // The shipped defaults pass: analysis 5 min, comparison 300 s.
        assertThat(analysisTarget.processingStaleAfter()).isGreaterThan(Duration.ofSeconds(245));
        assertThat(comparisonTarget.processingStaleAfter()).isGreaterThan(Duration.ofSeconds(245));
    }

    private SweepTarget target(Duration processing) {
        var target = mock(SweepTarget.class);
        org.mockito.Mockito.when(target.name()).thenReturn("synthetic");
        org.mockito.Mockito.when(target.pendingStaleAfter()).thenReturn(Duration.ofMinutes(10));
        org.mockito.Mockito.when(target.processingStaleAfter()).thenReturn(processing);
        org.mockito.Mockito.when(target.poolThreads()).thenReturn(8);
        org.mockito.Mockito.when(target.poolQueueCapacity()).thenReturn(16);
        return target;
    }

    private SweepTarget target(Duration pending, int threads, int capacity) {
        var target = target(Duration.ofSeconds(300));
        org.mockito.Mockito.when(target.pendingStaleAfter()).thenReturn(pending);
        org.mockito.Mockito.when(target.poolThreads()).thenReturn(threads);
        org.mockito.Mockito.when(target.poolQueueCapacity()).thenReturn(capacity);
        return target;
    }

    @Test
    void theDrainBootCheckRefusesAPendingThresholdThatDoesNotOutlastAFullQueue() {
        var ai = new AiServiceProperties("http://unused", "token", Duration.ofSeconds(5),
                Duration.ofSeconds(120), Duration.ofSeconds(5), 8192);
        // ceil(16 / 8) = 2 rounds x (2 x 120 s + 5 s) = 490 s
        assertThatThrownBy(() -> new StaleWorkSweeper(List.of(target(Duration.ofSeconds(490), 8, 16)), transactions, clock, ai))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pending-stale-after");
        new StaleWorkSweeper(List.of(target(Duration.ofSeconds(491), 8, 16)), transactions, clock, ai);
        // A queue that is not a whole number of rounds rounds up: ceil(17 / 8) = 3 rounds = 735 s.
        assertThatThrownBy(() -> new StaleWorkSweeper(List.of(target(Duration.ofSeconds(735), 8, 17)), transactions, clock, ai))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pending-stale-after");
        // No queue, nothing to wait for.
        new StaleWorkSweeper(List.of(target(Duration.ofSeconds(1), 8, 0)), transactions, clock, ai);
        // Every target is checked, so a failing second one still refuses the boot.
        assertThatThrownBy(() -> new StaleWorkSweeper(
                List.of(target(Duration.ofSeconds(491), 8, 16), target(Duration.ofSeconds(100), 8, 16)),
                transactions, clock, ai)).isInstanceOf(IllegalStateException.class);
        // The shipped defaults (16 / 8, 10 min) pass for both targets.
        assertThat(analysisTarget.pendingStaleAfter()).isGreaterThan(Duration.ofSeconds(490));
        assertThat(comparisonTarget.pendingStaleAfter()).isGreaterThan(Duration.ofSeconds(490));
        assertThat(analysisTarget.poolQueueCapacity()).isEqualTo(16);
        assertThat(comparisonTarget.poolThreads()).isEqualTo(8);
    }

    // --- AI outage scenarios ---

    @Test
    void anUnreachableAiServiceIsRetriedOnceThenTheAnalysisFails() throws Exception {
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(aResponse().withStatus(503)));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/resumes/analyze")
                .header("Authorization", bearerToken)).andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mockMvc
                .perform(get("/resumes/analysis").header("Authorization", bearerToken))
                .andExpect(jsonPath("$.status").value("FAILED")));
        aiService.verify(2, postRequestedFor(urlEqualTo("/resume")));
    }

    @Test
    void aHangingCallIsTimedOutByTheSweepAndItsLaterReplyIsDiscarded() {
        // A call that never returned leaves the row PROCESSING; its reply arrives after the sweep.
        UUID id = analysis(user, "PROCESSING", clock.instant(), clock.instant());
        clock.advance(Duration.ofMinutes(6));
        sweeper.sweep();
        assertThat(analysisRow(id)).containsEntry("error_code", "ANALYSIS_TIMED_OUT");
        assertThat(analyses.complete(id, new ScoresDTO(10, 20, 30, 40), "[]", "m", "v")).isZero();
        assertThat(analysisRow(id)).containsEntry("status", "FAILED");
    }

    @Test
    void workLostToARestartIsLeftAloneAtStartupThenTimedOutPastEachThresholdForBothTargets() {
        UUID pending = analysis(user, "PENDING", clock.instant(), null);
        UUID processing = analysis(otherUser(), "PROCESSING", clock.instant(), clock.instant());
        UUID lostEvent = savedJob("PENDING", clock.instant(), null);
        UUID lostLease = savedJob("PENDING", clock.instant(), clock.instant().plus(Duration.ofMinutes(5)));

        sweeper.sweep(); // the startup run
        assertThat(analysisRow(pending)).containsEntry("status", "PENDING");
        assertThat(analysisRow(processing)).containsEntry("status", "PROCESSING");

        clock.advance(Duration.ofMinutes(6)); // past processing-stale-after, before pending-stale-after
        sweeper.sweep();
        assertThat(analysisRow(processing)).containsEntry("status", "FAILED");
        assertThat(jobRow(lostLease)).containsEntry("comparison_status", "FAILED");
        assertThat(analysisRow(pending)).containsEntry("status", "PENDING");
        assertThat(jobRow(lostEvent)).containsEntry("comparison_status", "PENDING");

        clock.advance(Duration.ofMinutes(5)); // past pending-stale-after
        sweeper.sweep();
        assertThat(analysisRow(pending)).containsEntry("error_code", "ANALYSIS_TIMED_OUT");
        assertThat(jobRow(lostEvent)).containsEntry("comparison_error_code", "COMPARISON_TIMED_OUT");
    }
}
