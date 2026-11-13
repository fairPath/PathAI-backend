package com.job_search.fair_path.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import org.hibernate.annotations.UuidGenerator;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "resume")
@Getter
@Setter
public class Resume {

    public enum Status {
        PENDING, ACTIVE, DELETED
    }

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true) // matches DB intent
    private UUID userId;

    @Column(name = "s3_bucket", nullable = false)
    private String s3Bucket;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    // A re-upload reuses this row, so updatedAt is what marks a replaced file.
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
