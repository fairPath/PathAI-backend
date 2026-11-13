package com.job_search.fair_path.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "saved_jobs", uniqueConstraints = {
        @UniqueConstraint(name = "saved_jobs_user_job_unique", columnNames = { "user_id", "job_id" })
}, indexes = {
        @Index(name = "saved_jobs_user_created_idx", columnList = "user_id, created_at"),
        @Index(name = "saved_jobs_comparison_queue_idx", columnList = "comparison_status, comparison_next_attempt_at")
})
@Getter
@Setter
public class SavedJobsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "job_id", nullable = false)
    private String jobId;
    @Column(name = "company_name")
    private String companyName;
    @Column(name = "job_title")
    private String jobTitle;
    @Column(name = "redirect_url")
    private String redirectUrl;
    @Column(name = "date_created")
    private LocalDateTime dateCreated;
    @Column(name = "location")
    private String location;
    @Column(name = "job_description", columnDefinition = "text")
    private String jobDescription;
    @Column(name = "salary_min")
    private Double salaryMin;
    @Column(name = "salary_max")
    private Double salaryMax;

    @Column(name = "resume_id")
    private UUID resumeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resume_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> resumeSnapshot;

    @Column(name = "match_score", precision = 5, scale = 2)
    private BigDecimal matchScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_skills", columnDefinition = "jsonb")
    private List<String> matchedSkills;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_skills", columnDefinition = "jsonb")
    private List<String> missingSkills;

    @Column(name = "relevant_experience", columnDefinition = "text")
    private String relevantExperience;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "other_gaps", columnDefinition = "jsonb")
    private List<String> otherGaps;

    @Column(name = "comparison_status", length = 20)
    private String comparisonStatus;

    // The saved row is also the durable comparison queue entry.
    @Column(name = "comparison_request_id")
    private UUID comparisonRequestId;
    @Column(name = "comparison_lease_token")
    private UUID comparisonLeaseToken;
    @Column(name = "comparison_lease_until")
    private Instant comparisonLeaseUntil;
    @Column(name = "comparison_attempts")
    private Integer comparisonAttempts;
    @Column(name = "comparison_next_attempt_at")
    private Instant comparisonNextAttemptAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public SavedJobsEntity(String jobId, UUID userId, String companyName, String jobTitle, String redirectUrl,
            LocalDateTime dateCreated, String location,
            String jobDescription, Double salaryMin, Double salaryMax) {
        this.companyName = companyName;
        this.userId = userId;
        this.jobId = jobId;
        this.jobTitle = jobTitle;
        this.dateCreated = dateCreated;
        this.location = location;
        this.redirectUrl = redirectUrl;
        this.jobDescription = jobDescription;
        this.salaryMin = salaryMin;
        this.salaryMax = salaryMax;
    }

    public SavedJobsEntity() {
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (comparisonStatus == null) {
            comparisonStatus = "NOT_REQUESTED";
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
