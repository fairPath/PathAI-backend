package com.job_search.fair_path.services;

import com.job_search.fair_path.exceptions.ComparisonFailure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.support.IntegrationTestBase;

/** Runs on the shared Testcontainers Postgres, in a separate random schema, never application rows. */
class PostgresJobComparisonQueueTests extends IntegrationTestBase {
    @Autowired
    private JdbcConnectionDetails connection;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private JobComparisonQueue queue;
    private ComparisonSweepTarget sweep;
    private String schema;
    private UUID id;
    private UUID requestId;
    private final UUID userId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final JobComparisonClient.Result result =
            new JobComparisonClient.Result(80, List.of("Java"), List.of(), "Experience", List.of(), 90, 70, "Highlight your relevant projects.");

    @BeforeEach
    void setup() throws Exception {
        String url = connection.getJdbcUrl();
        String username = connection.getUsername();
        String password = connection.getPassword();
        var source = new DriverManagerDataSource(url, username, password);
        admin = new JdbcTemplate(source);
        schema = "comparison_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var isolatedSource = new DriverManagerDataSource(url, username, password);
        isolatedSource.setSchema(schema);
        jdbc = new JdbcTemplate(isolatedSource);
        jdbc.execute("CREATE TABLE users (id uuid PRIMARY KEY, enabled boolean)");
        jdbc.update("INSERT INTO users VALUES (?, true)", userId);
        jdbc.execute("CREATE TABLE resume (id uuid PRIMARY KEY, user_id uuid UNIQUE, s3_key text, status text, updated_at timestamptz)");
        jdbc.update("INSERT INTO resume VALUES (?, ?, 'active.pdf', 'ACTIVE', CURRENT_TIMESTAMP)", resumeId, userId);
        jdbc.execute("""
                CREATE TABLE saved_jobs (
                    id uuid PRIMARY KEY, job_id text, comparison_status text, user_id uuid,
                    resume_id uuid, comparison_resume_updated_at timestamptz, comparison_completed_at timestamptz,
                    comparison_error_code text, comparison_retryable boolean,
                    comparison_request_id uuid, comparison_lease_token uuid,
                    comparison_lease_until timestamptz, comparison_attempts integer,
                    comparison_next_attempt_at timestamptz,
                    job_description text, match_score numeric,
                    skill_match_score integer, experience_match_score integer, resume_advice text,
                    matched_skills jsonb, missing_skills jsonb, relevant_experience text,
                    other_gaps jsonb, created_at timestamptz DEFAULT CURRENT_TIMESTAMP,
                    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP
                )
                """);
        queue = new JobComparisonQueue(jdbc, new ObjectMapper());
        sweep = new ComparisonSweepTarget(jdbc, new com.job_search.fair_path.config.ComparisonProperties(
                java.time.Duration.ofMinutes(10), java.time.Duration.ofSeconds(300)),
                new com.job_search.fair_path.config.AiExecutionProperties(8, 8, 16, 16));
        id = UUID.randomUUID();
        requestId = UUID.randomUUID();
        insert(id, requestId);
    }

    private void insert(UUID rowId, UUID generation) {
        jdbc.update("""
                INSERT INTO saved_jobs (id, job_id, comparison_status, comparison_request_id,
                    comparison_attempts, job_description, user_id)
                VALUES (?, 'job', 'PENDING', ?, 0, 'Java', ?)
                """, rowId, generation, userId);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void expiredExecutionFailsWithoutReplayingInference() throws Exception {
        var first = queue.claim().orElseThrow();
        assertTrue(queue.claim().isEmpty());
        jdbc.update("UPDATE saved_jobs SET comparison_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertEquals(1, sweep.sweep(java.time.Instant.now()));
        assertTrue(queue.claim().isEmpty());
        assertEquals("FAILED", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs", String.class));
        assertFalse(queue.complete(first, result));
    }

    @Test
    void afterCommitDispatchClaimsOnlyItsOwnRequest() {
        UUID second = UUID.randomUUID();
        insert(UUID.randomUUID(), second);
        assertEquals(second, queue.claim(second).orElseThrow().requestId());
        assertTrue(queue.claim(second).isEmpty());
        assertEquals(requestId, queue.claim(requestId).orElseThrow().requestId());
    }

    @Test
    void workerReceivesWholeDescriptionFromSingleColumn() {
        String full = "A".repeat(1500) + " final requirement";
        jdbc.update("UPDATE saved_jobs SET job_description = ?", full);
        assertEquals(full, queue.claim().orElseThrow().description());
    }

    @Test
    void concurrentWorkersCannotClaimTheSameActiveTask() {
        var first = CompletableFuture.supplyAsync(queue::claim);
        var second = CompletableFuture.supplyAsync(queue::claim);
        assertEquals(1, (first.join().isPresent() ? 1 : 0) + (second.join().isPresent() ? 1 : 0));
    }

    @Test
    void unsaveAndResaveCannotReceiveAnOldResult() throws Exception {
        var old = queue.claim().orElseThrow();
        jdbc.update("DELETE FROM saved_jobs WHERE id = ?", id);
        insert(UUID.randomUUID(), UUID.randomUUID());
        assertFalse(queue.complete(old, result));
        queue.fail(old, new ComparisonFailure("AI_UNAVAILABLE", true));
        assertEquals("PENDING", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs", String.class));
        assertNull(jdbc.queryForObject("SELECT match_score FROM saved_jobs", Integer.class));
    }

    @Test
    void terminalFailureCannotBeClaimedAgainAutomatically() {
        var claimed = queue.claim().orElseThrow();
        queue.fail(claimed, new ComparisonFailure("INTERNAL_ERROR", true));
        assertEquals("FAILED", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs", String.class));
        assertTrue(queue.claim().isEmpty());
    }

    @Test
    void claimsLiveResumeAndRejectsResultAfterReplacement() throws Exception {
        var old = queue.claim().orElseThrow();
        assertEquals("active.pdf", old.s3Key());
        jdbc.update("UPDATE resume SET updated_at = updated_at + INTERVAL '1 second', s3_key = 'new.pdf'");
        assertFalse(queue.complete(old, result));
        queue.fail(old, new ComparisonFailure("RESUME_CHANGED", true));
        jdbc.update("UPDATE saved_jobs SET comparison_status = 'PENDING', comparison_attempts = 0, comparison_request_id = ?", UUID.randomUUID());
        var latest = queue.claim().orElseThrow();
        assertEquals("new.pdf", latest.s3Key());
        assertTrue(queue.complete(latest, result));
    }

    @Test
    void permanentFailureStopsAfterFirstAttemptAndStoresItsCode() {
        var task = queue.claim().orElseThrow();
        queue.fail(task, new ComparisonFailure("RESUME_UNREADABLE", false));
        assertTrue(queue.claim().isEmpty());
        assertEquals("FAILED", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs", String.class));
        assertEquals("RESUME_UNREADABLE", jdbc.queryForObject("SELECT comparison_error_code FROM saved_jobs", String.class));
        assertFalse(jdbc.queryForObject("SELECT comparison_retryable FROM saved_jobs", Boolean.class));
        assertEquals(1, jdbc.queryForObject("SELECT comparison_attempts FROM saved_jobs", Integer.class));
    }

    @Test
    void processDeathOnLastAttemptEventuallyBecomesFailed() {
        assertEquals(1, queue.claim().orElseThrow().attempt());
        jdbc.update("UPDATE saved_jobs SET comparison_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertEquals(1, sweep.sweep(java.time.Instant.now()));
        assertTrue(queue.claim().isEmpty());
        assertEquals("FAILED", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs", String.class));
    }
}
