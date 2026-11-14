package com.job_search.fair_path.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import com.job_search.fair_path.entity.Resume;

@Repository
public interface ResumeRepository extends JpaRepository<Resume, UUID> {
    Optional<Resume> findByIdAndUserId(UUID id, UUID userId);

    Optional<Resume> findByUserId(UUID userId);

    /**
     * Guarded status change (PENDING → ACTIVE): 0 rows means the Resume was
     * not in the expected status. A bulk update skips @PreUpdate, so
     * updated_at is set here.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Resume r set r.status = :to, r.updatedAt = :now
            where r.id = :id and r.userId = :userId and r.status = :from
            """)
    int changeStatus(@Param("id") UUID id, @Param("userId") UUID userId, @Param("from") Resume.Status from,
            @Param("to") Resume.Status to, @Param("now") Instant now);

}
