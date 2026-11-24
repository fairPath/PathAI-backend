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
    public record Task(String tokenHash, UUID lease, UUID owner, String bucket) {}

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
                RETURNING token_hash, lease, owner_id, bucket
                """, (rs, row) -> new Task(rs.getString("token_hash"), rs.getObject("lease", UUID.class),
                        rs.getObject("owner_id", UUID.class), rs.getString("bucket")),
                UUID.randomUUID(), Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now))
                .stream().findFirst();
    }

    public boolean renew(Task task, Instant now, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE account_deletion SET lease_until = ?
                WHERE token_hash = ? AND lease = ? AND lease_until > ?
                """, Timestamp.from(leaseUntil), task.tokenHash(), task.lease(), Timestamp.from(now)) == 1;
    }

    public Optional<String> beginNotification(Task task, Instant now) {
        // Record the attempt before contacting SMTP so a recovered lease cannot send it again.
        return jdbc.query("""
                UPDATE account_deletion SET notification_started = true
                WHERE token_hash = ? AND lease = ? AND lease_until > ? AND NOT notification_started
                RETURNING (SELECT email FROM users WHERE id = owner_id)
                """, (rs, row) -> rs.getString(1), task.tokenHash(), task.lease(), Timestamp.from(now))
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Transactional
    public void finishCleanup(Task task, Instant now) {
        int deleted = jdbc.update("""
                DELETE FROM account_deletion
                WHERE token_hash = ? AND lease = ? AND lease_until > ? AND owner_id = ?
                """, task.tokenHash(), task.lease(), Timestamp.from(now), task.owner());
        if (deleted == 0) return;

        jdbc.update("DELETE FROM analysis WHERE user_id = ?", task.owner());
        jdbc.update("DELETE FROM saved_jobs WHERE user_id = ?", task.owner());
        jdbc.update("DELETE FROM usage_record WHERE user_id = ?", task.owner());
        jdbc.update("DELETE FROM resume WHERE user_id = ?", task.owner());
        jdbc.update("DELETE FROM users WHERE id = ?", task.owner());
    }

    public void retry(Task task, Instant dueAt, String errorCode) {
        jdbc.update("""
                UPDATE account_deletion SET due_at = ?, lease = NULL, lease_until = NULL,
                    attempts = attempts + 1, last_error = ?
                WHERE token_hash = ? AND lease = ?
                """, Timestamp.from(dueAt), errorCode, task.tokenHash(), task.lease());
    }
}
