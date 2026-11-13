package com.job_search.fair_path.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One run of the pipeline on one Resume, kept whatever its outcome. Its id is
 * the correlation_id sent to the AI service.
 *
 * Status changes never go through save(): every transition is a guarded
 * compare-and-set in AnalysisRepository.
 */
@Entity
@Table(name = "analysis")
@Getter
@Setter
public class Analysis {

    /** PENDING → PROCESSING → COMPLETED, and PENDING/PROCESSING → FAILED. */
    public enum Status {
        PENDING, PROCESSING, COMPLETED, FAILED
    }

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Resume resume;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Set on the move to PROCESSING. */
    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "score_formatting")
    private Integer scoreFormatting;

    @Column(name = "score_parseability")
    private Integer scoreParseability;

    @Column(name = "score_writing")
    private Integer scoreWriting;

    @Column(name = "score_overall")
    private Integer scoreOverall;

    /** The Issues as returned by the AI service (IssueDetail JSON array). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issues")
    private String issues;

    @Column(name = "model")
    private String model;

    @Column(name = "pipeline_version")
    private String pipelineVersion;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
