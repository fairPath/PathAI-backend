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

    List<SavedJobsEntity> deleteByUserIdAndJobId(UUID userId, String jobId);

}
