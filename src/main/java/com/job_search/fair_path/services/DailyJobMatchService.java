package com.job_search.fair_path.services;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DailyJobMatchService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EmailService email;
    private final Clock clock;
    private final String settingsUrl;
    private final ZoneId zone;
    private final LocalTime sendTime;
    private final Duration deliveryWindow;
    private final Duration retryDelay;
    private final int maxRetries;
    private final int batchSize;

    public DailyJobMatchService(JdbcTemplate jdbc, TransactionTemplate transactions, EmailService email,
            Clock clock, @Value("${fairpath.daily-job-match.settings-url}") String settingsUrl,
            @Value("${fairpath.daily-job-match.zone}") String zone,
            @Value("${fairpath.daily-job-match.send-time}") String sendTime,
            @Value("${fairpath.daily-job-match.delivery-window}") Duration deliveryWindow,
            @Value("${fairpath.daily-job-match.retry-delay}") Duration retryDelay,
            @Value("${fairpath.daily-job-match.max-retries}") int maxRetries,
            @Value("${fairpath.daily-job-match.batch-size}") int batchSize) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.email = email;
        this.clock = clock;
        this.settingsUrl = settingsUrl;
        this.zone = ZoneId.of(zone);
        this.sendTime = LocalTime.parse(sendTime);
        this.deliveryWindow = deliveryWindow;
        this.retryDelay = retryDelay;
        this.maxRetries = maxRetries;
        this.batchSize = batchSize;
    }

    public boolean isEnabled(UUID userId) {
        return preference(userId, false);
    }

    @Transactional
    public boolean setEnabled(UUID userId, boolean enabled) {
        if (preference(userId, true) == enabled) return enabled;

        Instant now = clock.instant();
        jdbc.update("""
                UPDATE users SET daily_job_match_enabled = ?, daily_job_match_enabled_at = ?
                WHERE id = ?
                """, enabled, enabled ? Timestamp.from(now) : null, userId);
        if (enabled) {
            jdbc.update("""
                    INSERT INTO daily_job_match_delivery (user_id, delivery_date, kind, available_at, expires_at)
                    VALUES (?, ?, 'IMMEDIATE', ?, ?)
                    ON CONFLICT (user_id, delivery_date, kind) DO NOTHING
                    """, userId, now.atZone(zone).toLocalDate(), Timestamp.from(now),
                    Timestamp.from(now.plus(deliveryWindow)));
        } else {
            jdbc.update("""
                    UPDATE daily_job_match_delivery SET status = 'CANCELED'
                    WHERE user_id = ? AND status = 'PENDING'
                    """, userId);
        }
        return enabled;
    }

    private boolean preference(UUID userId, boolean lock) {
        String sql = """
                SELECT daily_job_match_enabled FROM users WHERE id = ? AND enabled
                """;
        return jdbc.query(sql + (lock ? " FOR UPDATE" : ""), (rs, row) -> rs.getBoolean(1), userId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public void poll() {
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE daily_job_match_delivery SET status = 'FAILED'
                WHERE status = 'PENDING' AND expires_at <= ?
                """, Timestamp.from(now));
        enqueueScheduled(now);
        for (int processed = 0; processed < batchSize; processed++) {
            var next = nextDelivery(clock.instant());
            if (next.isEmpty()) return;

            var delivery = next.get();
            var attempt = claim(delivery);
            attempt.ifPresent(claimed ->
                    transactions.executeWithoutResult(transaction -> deliver(delivery, claimed)));
        }
    }

    private Optional<DeliveryKey> nextDelivery(Instant now) {
        return jdbc.query("""
                SELECT d.user_id, d.delivery_date, d.kind
                FROM daily_job_match_delivery d JOIN users u ON u.id = d.user_id
                WHERE d.status = 'PENDING' AND d.available_at <= ? AND d.expires_at > ?
                    AND u.enabled AND u.daily_job_match_enabled
                ORDER BY (d.kind = 'IMMEDIATE' AND d.attempts = 0) DESC,
                    d.available_at, d.user_id, d.delivery_date, d.kind
                LIMIT 1
                """, (rs, row) -> new DeliveryKey(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                rs.getString(3)), Timestamp.from(now), Timestamp.from(now)).stream().findFirst();
    }

    private void enqueueScheduled(Instant now) {
        LocalDate date = now.atZone(zone).toLocalDate();
        Instant scheduled = date.atTime(sendTime).atZone(zone).toInstant();
        Instant expires = scheduled.plus(deliveryWindow);
        if (now.isBefore(scheduled) || !now.isBefore(expires)) return;

        jdbc.update("""
                INSERT INTO daily_job_match_delivery (user_id, delivery_date, kind, available_at, expires_at)
                SELECT u.id, ?, 'SCHEDULED', ?, ? FROM users u
                WHERE u.enabled AND u.daily_job_match_enabled AND u.daily_job_match_enabled_at <= ?
                    AND NOT EXISTS (
                        SELECT 1 FROM daily_job_match_delivery d
                        WHERE d.user_id = u.id AND d.delivery_date = ? AND d.kind = 'SCHEDULED'
                    )
                ON CONFLICT (user_id, delivery_date, kind) DO NOTHING
                """, date, Timestamp.from(scheduled), Timestamp.from(expires), Timestamp.from(scheduled), date);
    }

    private Optional<Attempt> claim(DeliveryKey key) {
        Instant now = clock.instant();
        // Commit the attempt before calling SMTP. An interrupted attempt stays terminal.
        return jdbc.query("""
                UPDATE daily_job_match_delivery d SET status = 'FAILED', attempts = d.attempts + 1
                FROM users u
                WHERE d.user_id = ? AND d.delivery_date = ? AND d.kind = ? AND d.status = 'PENDING'
                    AND d.available_at <= ? AND d.expires_at > ?
                    AND u.id = d.user_id AND u.enabled AND u.daily_job_match_enabled
                RETURNING d.attempts, d.expires_at, u.daily_job_match_enabled_at
                """, (rs, row) -> new Attempt(rs.getInt(1), rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3).toInstant()), key.userId(), key.date(), key.kind(),
                Timestamp.from(now), Timestamp.from(now)).stream().findFirst();
    }

    private void deliver(DeliveryKey key, Attempt attempt) {
        // Serialize handoff to SMTP with preference changes and account deletion.
        var recipient = jdbc.query("""
                SELECT email, first_name, username, daily_job_match_enabled_at
                FROM users WHERE id = ? AND enabled AND daily_job_match_enabled
                FOR UPDATE
                """, (rs, row) -> {
                    String name = rs.getString("first_name");
                    return new Recipient(rs.getString("email"),
                            name == null || name.isBlank() ? rs.getString("username") : name,
                            rs.getTimestamp("daily_job_match_enabled_at").toInstant());
                }, key.userId()).stream().findFirst();
        if (recipient.isEmpty() || !recipient.get().enabledAt().equals(attempt.enabledAt())) return;
        var user = recipient.get();
        if (key.kind().equals("SCHEDULED")
                && user.enabledAt().isAfter(key.date().atTime(sendTime).atZone(zone).toInstant())) return;

        Instant now = clock.instant();
        if (!now.isBefore(attempt.expiresAt())) return;
        var outcome = EmailDelivery.attempt("Daily Job Match " + key.kind(),
                () -> email.sendDailyJobMatch(user.address(), user.name(), settingsUrl));
        if (outcome == EmailDelivery.Outcome.SENT) {
            finish(key, attempt, "SENT", now, attempt.expiresAt());
        } else if (outcome == EmailDelivery.Outcome.RETRYABLE_FAILURE && attempt.count() <= maxRetries) {
            Instant retryAt = clock.instant().plus(retryDelay);
            Instant expires = attempt.count() == 1 ? now.plus(deliveryWindow) : attempt.expiresAt();
            if (retryAt.isBefore(expires)) finish(key, attempt, "PENDING", retryAt, expires);
        }
    }

    private void finish(DeliveryKey key, Attempt attempt, String status, Instant availableAt, Instant expiresAt) {
        jdbc.update("""
                UPDATE daily_job_match_delivery
                SET status = ?, available_at = ?, expires_at = ?
                WHERE user_id = ? AND delivery_date = ? AND kind = ? AND status = 'FAILED' AND attempts = ?
                """, status, Timestamp.from(availableAt), Timestamp.from(expiresAt),
                key.userId(), key.date(), key.kind(), attempt.count());
    }

    private record DeliveryKey(UUID userId, LocalDate date, String kind) {}
    private record Recipient(String address, String name, Instant enabledAt) {}
    private record Attempt(int count, Instant expiresAt, Instant enabledAt) {}
}
