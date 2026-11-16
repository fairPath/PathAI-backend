package com.job_search.fair_path.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.job_search.fair_path.entity.SavedJobsEntity;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

@Repository
public interface SavedJobsRepository extends JpaRepository<SavedJobsEntity, UUID> {
    List<SavedJobsEntity> findByUserId(UUID userId);

    List<SavedJobsEntity> findByUserIdAndJobIdIn(UUID userId, List<String> jobIds);

    long countByUserId(UUID userId);

    Optional<SavedJobsEntity> findByUserIdAndJobId(UUID userId, String jobId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select j from SavedJobsEntity j where j.userId = :userId and j.jobId = :jobId")
    Optional<SavedJobsEntity> findForUpdate(@org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("jobId") String jobId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = """
            UPDATE saved_jobs SET comparison_status = :next, comparison_request_id = :requestId,
                comparison_attempts = 0, comparison_next_attempt_at = CURRENT_TIMESTAMP,
                comparison_lease_token = NULL, comparison_lease_until = NULL,
                comparison_error_code = NULL, comparison_retryable = NULL,
                match_score = NULL, skill_match_score = NULL, experience_match_score = NULL, resume_advice = NULL,
                matched_skills = NULL, missing_skills = NULL,
                relevant_experience = NULL, other_gaps = NULL, resume_id = NULL,
                comparison_resume_updated_at = NULL, comparison_completed_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND user_id = :userId
                AND COALESCE(comparison_status, '') = COALESCE(:expected, '')
            """, nativeQuery = true)
    int changeComparison(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("expected") String expected,
            @org.springframework.data.repository.query.Param("next") String next,
            @org.springframework.data.repository.query.Param("requestId") UUID requestId);

    List<SavedJobsEntity> deleteByUserIdAndJobId(UUID userId, String jobId);

}
