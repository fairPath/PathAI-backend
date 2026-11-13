package com.job_search.fair_path.services;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Each claim commits before inference starts. No database connection is held during AI work. */
@Repository
public class JobComparisonQueue {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record Task(UUID savedJobId, UUID requestId, UUID leaseToken, String jobId,
            String s3Key, String description, int attempt) {}

    public JobComparisonQueue(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Task> claim() {
        // A process killed on its last attempt must eventually leave PENDING too.
        jdbc.update("""
                UPDATE saved_jobs SET comparison_status = 'FAILED', comparison_lease_token = NULL,
                    comparison_lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE comparison_status = 'PENDING' AND comparison_attempts >= 3
                    AND comparison_lease_until <= CURRENT_TIMESTAMP
                """);
        return jdbc.query("""
                UPDATE saved_jobs AS job SET
                    comparison_request_id = COALESCE(job.comparison_request_id, ?),
                    comparison_lease_token = ?,
                    comparison_lease_until = CURRENT_TIMESTAMP + INTERVAL '180 seconds',
                    comparison_attempts = COALESCE(job.comparison_attempts, 0) + 1
                WHERE job.id = (
                    SELECT id FROM saved_jobs
                    WHERE comparison_status = 'PENDING' AND COALESCE(comparison_attempts, 0) < 3
                        AND (comparison_next_attempt_at IS NULL OR comparison_next_attempt_at <= CURRENT_TIMESTAMP)
                        AND (comparison_lease_until IS NULL OR comparison_lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY comparison_next_attempt_at NULLS FIRST, created_at
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                RETURNING job.id, job.comparison_request_id, job.comparison_lease_token, job.job_id,
                    job.resume_snapshot ->> 's3Key' AS s3_key,
                    COALESCE(job.job_description, '') AS description,
                    job.comparison_attempts
                """, (rs, row) -> new Task(rs.getObject("id", UUID.class),
                        rs.getObject("comparison_request_id", UUID.class),
                        rs.getObject("comparison_lease_token", UUID.class), rs.getString("job_id"),
                        rs.getString("s3_key"), rs.getString("description"), rs.getInt("comparison_attempts")),
                UUID.randomUUID(), UUID.randomUUID()).stream().findFirst();
    }

    public boolean complete(Task task, JobComparisonClient.Result result) throws JsonProcessingException {
        result.validate();
        return jdbc.update("""
                UPDATE saved_jobs SET comparison_status = 'COMPLETED', match_score = ?,
                    matched_skills = CAST(? AS jsonb), missing_skills = CAST(? AS jsonb),
                    relevant_experience = ?, other_gaps = CAST(? AS jsonb),
                    comparison_lease_token = NULL, comparison_lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND comparison_request_id = ? AND comparison_lease_token = ?
                    AND comparison_status = 'PENDING' AND comparison_lease_until > CURRENT_TIMESTAMP
                """, result.matchScore(), mapper.writeValueAsString(result.matchedSkills()),
                mapper.writeValueAsString(result.missingSkills()), result.relevantExperience(),
                mapper.writeValueAsString(result.otherGaps()), task.savedJobId(), task.requestId(), task.leaseToken()) == 1;
    }

    public void retryOrFail(Task task) {
        jdbc.update("""
                UPDATE saved_jobs SET comparison_status = CASE WHEN comparison_attempts >= 3 THEN 'FAILED' ELSE 'PENDING' END,
                    comparison_next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '10 seconds' * comparison_attempts,
                    comparison_lease_token = NULL, comparison_lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND comparison_request_id = ? AND comparison_lease_token = ?
                    AND comparison_status = 'PENDING' AND comparison_lease_until > CURRENT_TIMESTAMP
                """, task.savedJobId(), task.requestId(), task.leaseToken());
    }
}
