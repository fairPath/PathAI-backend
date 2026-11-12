package com.job_search.fair_path.services;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Uses a separate random schema, never application rows. Point only at a disposable test database. */
@EnabledIfEnvironmentVariable(named = "FAIRPATH_TEST_DB_URL", matches = ".+")
class PostgresJobComparisonQueueTests {
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
        String url = System.getenv("FAIRPATH_TEST_DB_URL");
        String username = System.getenv().getOrDefault("FAIRPATH_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("FAIRPATH_TEST_DB_PASSWORD", "");
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
                    resume_snapshot jsonb, job_description text, match_score numeric,
                    matched_skills jsonb, missing_skills jsonb, relevant_experience text,
                    other_gaps jsonb, created_at timestamptz DEFAULT CURRENT_TIMESTAMP,
                    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP
                )
                """);
        String migration = Files.readString(Path.of("scripts/migrations/20261111_background_comparisons.sql"));
        for (String sql : migration.split(";")) {
            if (!sql.isBlank()) jdbc.execute(sql);
        }
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

    private void migrateDescription() throws Exception {
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/20261112_single_job_description.sql")));
    }

    private void addConsecutiveColumns() {
        jdbc.execute("ALTER TABLE saved_jobs ALTER COLUMN job_description TYPE varchar(255)");
        jdbc.execute("ALTER TABLE saved_jobs ADD COLUMN job_description1 varchar(252), ADD COLUMN job_description2 text");
    }

    private void assertSingleTextColumn() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'saved_jobs' AND column_name IN ('job_description1', 'job_description2', 'job_description3')", Integer.class, schema));
        assertEquals("text", jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = 'saved_jobs' AND column_name = 'job_description'", String.class, schema));
    }

    @Test
    void migrationPreservesOldSegmentsDropsDuplicateAndIsIdempotent() throws Exception {
        addConsecutiveColumns();
        jdbc.execute("ALTER TABLE saved_jobs ADD COLUMN job_description3 text");
        String first = "A".repeat(251) + "\uD83D\uDE00";
        String second = "B".repeat(252);
        String third = "C".repeat(252);
        String full = first + second + third;
        jdbc.update("UPDATE saved_jobs SET job_description = ?, job_description1 = ?, job_description2 = ?, job_description3 = ?",
                first + "...", first, second, third);
        migrateDescription();
        assertEquals(full, jdbc.queryForObject("SELECT job_description FROM saved_jobs", String.class));
        assertSingleTextColumn();
        migrateDescription();
        assertEquals(full, queue.claim().orElseThrow().description());
    }

    @Test
    void migrationPreservesConsecutiveSegmentsAndExpandsBaseBeforeCombining() throws Exception {
        addConsecutiveColumns();
        String first = "A".repeat(255);
        String second = "B".repeat(252);
        String remainder = "C".repeat(1500) + " end";
        jdbc.update("UPDATE saved_jobs SET job_description = ?, job_description1 = ?, job_description2 = ?",
                first, second, remainder);
        migrateDescription();
        assertSingleTextColumn();
        assertEquals(first + second + remainder, queue.claim().orElseThrow().description());
    }

    @Test
    void migrationPreservesLegacyBaseOnlyRows() throws Exception {
        addConsecutiveColumns();
        jdbc.execute("ALTER TABLE saved_jobs ADD COLUMN job_description3 text");
        jdbc.update("UPDATE saved_jobs SET job_description = 'Legacy description'");
        migrateDescription();
        assertSingleTextColumn();
        assertEquals("Legacy description", queue.claim().orElseThrow().description());
    }

    @Test
    void migrationLeavesAnExistingSingleColumnValueUnchanged() throws Exception {
        jdbc.update("UPDATE saved_jobs SET job_description = 'Already consolidated'");
        migrateDescription();
        migrateDescription();
        assertSingleTextColumn();
        assertEquals("Already consolidated", queue.claim().orElseThrow().description());
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
