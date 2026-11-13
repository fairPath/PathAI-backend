package com.job_search.fair_path.services;

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
    private String schema;
    private UUID id;
    private UUID requestId;
    private final JobComparisonClient.Result result =
            new JobComparisonClient.Result(80, List.of("Java"), List.of(), "Experience", List.of());

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
        jdbc.execute("""
                CREATE TABLE saved_jobs (
                    id uuid PRIMARY KEY, job_id text, comparison_status text,
                    comparison_request_id uuid, comparison_lease_token uuid,
                    comparison_lease_until timestamptz, comparison_attempts integer,
                    comparison_next_attempt_at timestamptz,
                    resume_snapshot jsonb, job_description text, match_score numeric,
                    matched_skills jsonb, missing_skills jsonb, relevant_experience text,
                    other_gaps jsonb, created_at timestamptz DEFAULT CURRENT_TIMESTAMP,
                    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP
                )
                """);
        queue = new JobComparisonQueue(jdbc, new ObjectMapper());
        id = UUID.randomUUID();
        requestId = UUID.randomUUID();
        insert(id, requestId);
    }

    private void insert(UUID rowId, UUID generation) {
        jdbc.update("""
                INSERT INTO saved_jobs (id, job_id, comparison_status, comparison_request_id,
                    comparison_attempts, resume_snapshot, job_description)
                VALUES (?, 'job', 'PENDING', ?, 0, '{"s3Key":"snapshot.pdf"}'::jsonb, 'Java')
                """, rowId, generation);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void restartRecoversExpiredLeaseAndRejectsOldWorkerResult() throws Exception {
        var first = queue.claim().orElseThrow();
        var restarted = new JobComparisonQueue(jdbc, new ObjectMapper());
        assertTrue(restarted.claim().isEmpty());
        jdbc.update("UPDATE saved_jobs SET comparison_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var recovered = restarted.claim().orElseThrow();
        assertEquals(first.requestId(), recovered.requestId());
        assertNotEquals(first.leaseToken(), recovered.leaseToken());
        assertEquals(2, recovered.attempt());
        assertFalse(queue.complete(first, result));
        assertTrue(restarted.complete(recovered, result));
        assertEquals(80, jdbc.queryForObject("SELECT match_score FROM saved_jobs", Integer.class));
        assertTrue(queue.claim().isEmpty());
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
        queue.retryOrFail(old);
        assertEquals("PENDING", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs", String.class));
        assertNull(jdbc.queryForObject("SELECT match_score FROM saved_jobs", Integer.class));
    }

    @Test
    void failuresStopAfterThreeAttemptsAndSurviveQueueRecreation() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claimed = new JobComparisonQueue(jdbc, new ObjectMapper()).claim().orElseThrow();
            assertEquals(attempt, claimed.attempt());
            queue.retryOrFail(claimed);
            if (attempt < 3) {
                assertTrue(queue.claim().isEmpty()); // retry backoff is enforced
                jdbc.update("UPDATE saved_jobs SET comparison_next_attempt_at = CURRENT_TIMESTAMP");
            }
        }
        assertEquals("FAILED", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs", String.class));
        assertTrue(queue.claim().isEmpty());
    }

    @Test
    void processDeathOnLastAttemptEventuallyBecomesFailed() {
        jdbc.update("UPDATE saved_jobs SET comparison_attempts = 2");
        assertEquals(3, queue.claim().orElseThrow().attempt());
        jdbc.update("UPDATE saved_jobs SET comparison_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        assertTrue(queue.claim().isEmpty());
        assertEquals("FAILED", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs", String.class));
    }
}
