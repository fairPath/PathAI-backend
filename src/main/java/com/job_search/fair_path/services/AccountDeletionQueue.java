package com.job_search.fair_path.services;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountDeletionQueue {
    public record Task(String tokenHash, UUID lease, UUID owner, String bucket, String key,
            String email, Instant emailDeadline) {}

    private final JdbcTemplate jdbc;

    public AccountDeletionQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Task> claim(Instant now, Instant leaseUntil) {
        return jdbc.query("""
                UPDATE account_deletion SET lease = ?, lease_until = ?
                WHERE token_hash = (
                    SELECT token_hash FROM account_deletion
                    WHERE due_at <= ? AND (lease_until IS NULL OR lease_until <= ?)
                    ORDER BY due_at LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                RETURNING token_hash, lease, owner_id, bucket, object_key, email, email_deadline
                """, (rs, row) -> {
                    Timestamp deadline = rs.getTimestamp("email_deadline");
                    return new Task(rs.getString("token_hash"), rs.getObject("lease", UUID.class),
                            rs.getObject("owner_id", UUID.class), rs.getString("bucket"),
                            rs.getString("object_key"), rs.getString("email"),
                            deadline == null ? null : deadline.toInstant());
                }, UUID.randomUUID(), Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now))
                .stream().findFirst();
    }

    @Transactional
    public void finishCleanup(Task task, Instant now, Instant emailDeadline) {
        int updated = jdbc.update("""
                UPDATE account_deletion SET
                    email = (SELECT email FROM users WHERE id = owner_id),
                    owner_id = NULL, bucket = NULL, object_key = NULL,
                    email_deadline = ?, due_at = ?, lease = NULL, lease_until = NULL
                WHERE token_hash = ? AND lease = ? AND lease_until > ? AND owner_id = ?
                """, Timestamp.from(emailDeadline), Timestamp.from(now), task.tokenHash(), task.lease(),
                Timestamp.from(now), task.owner());
        if (updated == 0) return;

        jdbc.update("DELETE FROM analysis WHERE user_id = ?", task.owner());
        jdbc.update("DELETE FROM saved_jobs WHERE user_id = ?", task.owner());
        jdbc.update("DELETE FROM usage_record WHERE user_id = ?", task.owner());
        jdbc.update("DELETE FROM resume WHERE user_id = ?", task.owner());
        jdbc.update("DELETE FROM users WHERE id = ?", task.owner());
    }

    public void retry(Task task, Instant dueAt) {
        jdbc.update("""
                UPDATE account_deletion SET due_at = ?, lease = NULL, lease_until = NULL
                WHERE token_hash = ? AND lease = ?
                """, Timestamp.from(dueAt), task.tokenHash(), task.lease());
    }

    public void forget(Task task) {
        jdbc.update("""
                DELETE FROM account_deletion
                WHERE token_hash = ? AND lease = ? AND owner_id IS NULL
                """, task.tokenHash(), task.lease());
    }
}
