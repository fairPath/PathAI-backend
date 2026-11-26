package com.job_search.fair_path.services;

import com.job_search.fair_path.exceptions.ComparisonFailure;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Claims commit before inference. Resolve the user's live resume on every attempt. */
@Repository
public class JobComparisonQueue {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    /** The lease length: a claimed row still PENDING after it is swept as stuck. */
    @Value("${fairpath.comparison.processing-stale-after:300s}")
    private Duration processingStaleAfter = Duration.ofSeconds(300);

    public record Task(UUID savedJobId, UUID requestId, UUID leaseToken, String jobId,
            String s3Key, String description, int attempt, UUID resumeId, Instant resumeUpdatedAt) {}

    public JobComparisonQueue(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Task> claim() { return claim(null); }

    public Optional<Task> claim(UUID requestId) {
        return jdbc.query("""
                UPDATE saved_jobs AS job SET
                    comparison_request_id = COALESCE(job.comparison_request_id, ?),
                    comparison_lease_token = ?,
                    comparison_lease_until = CURRENT_TIMESTAMP + INTERVAL '1 second' * ?,
                    comparison_attempts = COALESCE(job.comparison_attempts, 0) + 1
                WHERE job.id = (
                    SELECT id FROM saved_jobs
                    WHERE comparison_status = 'PENDING'
                        AND EXISTS (SELECT 1 FROM users u WHERE u.id = saved_jobs.user_id AND u.enabled)
                        AND COALESCE(comparison_attempts, 0) = 0
                        AND (CAST(? AS uuid) IS NULL OR comparison_request_id = ?)
                        AND (comparison_next_attempt_at IS NULL OR comparison_next_attempt_at <= CURRENT_TIMESTAMP)
                        AND (comparison_lease_until IS NULL OR comparison_lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY comparison_next_attempt_at NULLS FIRST, created_at
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                RETURNING job.id, job.comparison_request_id, job.comparison_lease_token, job.job_id,
                    (SELECT r.s3_key FROM resume r WHERE r.user_id = job.user_id AND r.status = 'ACTIVE') AS s3_key,
                    (SELECT r.id FROM resume r WHERE r.user_id = job.user_id AND r.status = 'ACTIVE') AS resume_id,
                    (SELECT r.updated_at FROM resume r WHERE r.user_id = job.user_id AND r.status = 'ACTIVE') AS resume_updated_at,
                    COALESCE(job.job_description, '') AS description, job.comparison_attempts
                """, (rs, row) -> {
                    Timestamp version = rs.getTimestamp("resume_updated_at");
                    return new Task(rs.getObject("id", UUID.class),
                        rs.getObject("comparison_request_id", UUID.class),
                        rs.getObject("comparison_lease_token", UUID.class), rs.getString("job_id"),
                        rs.getString("s3_key"), rs.getString("description"), rs.getInt("comparison_attempts"),
                        rs.getObject("resume_id", UUID.class), version == null ? null : version.toInstant());
                }, UUID.randomUUID(), UUID.randomUUID(), processingStaleAfter.toSeconds(), requestId, requestId).stream().findFirst();
    }

    public boolean complete(Task task, JobComparisonClient.Result result) throws JsonProcessingException {
        result.validate();
        return jdbc.update("""
                UPDATE saved_jobs AS job SET comparison_status = 'COMPLETED', match_score = ?,
                    skill_match_score = ?, experience_match_score = ?, resume_advice = ?,
                    matched_skills = CAST(? AS jsonb), missing_skills = CAST(? AS jsonb),
                    relevant_experience = ?, other_gaps = CAST(? AS jsonb),
                    resume_id = ?, comparison_resume_updated_at = ?, comparison_completed_at = CURRENT_TIMESTAMP,
                    comparison_error_code = NULL, comparison_retryable = NULL,
                    comparison_lease_token = NULL, comparison_lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE job.id = ? AND comparison_request_id = ? AND comparison_lease_token = ?
                    AND comparison_status = 'PENDING' AND comparison_lease_until > CURRENT_TIMESTAMP
                    AND EXISTS (SELECT 1 FROM users u WHERE u.id = job.user_id AND u.enabled FOR SHARE)
                    AND EXISTS (SELECT 1 FROM resume r WHERE r.user_id = job.user_id
                        AND r.id = ? AND r.updated_at = ? AND r.status = 'ACTIVE' AND r.s3_key = ?)
                """, result.matchScore(), result.skillMatchScore(), result.experienceMatchScore(), result.resumeAdvice(),
                mapper.writeValueAsString(result.matchedSkills()),
                mapper.writeValueAsString(result.missingSkills()), result.relevantExperience(),
                mapper.writeValueAsString(result.otherGaps()), task.resumeId(), Timestamp.from(task.resumeUpdatedAt()),
                task.savedJobId(), task.requestId(), task.leaseToken(), task.resumeId(),
                Timestamp.from(task.resumeUpdatedAt()), task.s3Key()) == 1;
    }

    public void fail(Task task, ComparisonFailure failure) {
        jdbc.update("""
                UPDATE saved_jobs SET comparison_status = 'FAILED',
                    comparison_error_code = ?, comparison_retryable = ?,
                    comparison_next_attempt_at = NULL,
                    comparison_lease_token = NULL, comparison_lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND comparison_request_id = ? AND comparison_lease_token = ?
                    AND comparison_status = 'PENDING' AND comparison_lease_until > CURRENT_TIMESTAMP
                """, failure.errorCode(), failure.retryable(),
                task.savedJobId(), task.requestId(), task.leaseToken());
    }
}
