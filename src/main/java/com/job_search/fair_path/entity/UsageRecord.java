package com.job_search.fair_path.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One counted use of the service, kept after the Resume it came from is gone.
 * Append-only, and it holds nothing from a Resume: no resume_id, analysis_id,
 * error code, Scores or Snippets. The Cooldown (and later the caps) are
 * queries over these rows.
 *
 * All three kinds are declared now: Hibernate puts them in a CHECK constraint
 * that ddl-auto=update cannot widen later. Only ANALYSIS_COMPLETED is written
 * so far.
 */
@Entity
@Table(name = "usage_record")
@Getter
@NoArgsConstructor
public class UsageRecord {

    public enum Kind {
        ANALYSIS_COMPLETED, ANALYSIS_FAILED, REWORD
    }

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private Kind kind;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public UsageRecord(User user, Kind kind, Instant occurredAt) {
        this.user = user;
        this.kind = kind;
        this.occurredAt = occurredAt;
    }
}
