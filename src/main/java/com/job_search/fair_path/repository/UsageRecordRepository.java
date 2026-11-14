package com.job_search.fair_path.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.job_search.fair_path.entity.UsageRecord;
import com.job_search.fair_path.entity.UsageRecord.Kind;

@Repository
public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    /** When the user's latest use of this kind happened, if ever. */
    @Query("select max(u.occurredAt) from UsageRecord u where u.user.id = :userId and u.kind = :kind")
    Optional<Instant> latestOccurredAt(@Param("userId") UUID userId, @Param("kind") Kind kind);
}
