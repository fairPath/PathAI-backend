package com.job_search.fair_path.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.job_search.fair_path.dataTransferObject.analysis.ScoresDTO;
import com.job_search.fair_path.entity.Analysis;
import com.job_search.fair_path.entity.Analysis.Status;

/**
 * Every status change is a guarded compare-and-set:
 * {@code UPDATE … WHERE id = ? AND status = <expected>}. Each returns the
 * rows-affected count; 0 means the row was not in the expected status
 * (a stale claim or a late result) and the caller must not act on it.
 */
@Repository
public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

    Optional<Analysis> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /** PENDING → PROCESSING, setting started_at. */
    default int claim(UUID id, Instant startedAt) {
        return markProcessing(id, startedAt, Status.PENDING, Status.PROCESSING);
    }

    /** PROCESSING → COMPLETED, storing the result in the same guarded update. */
    default int complete(UUID id, ScoresDTO scores, String issuesJson, String model, String pipelineVersion) {
        return storeResult(id, scores.formatting(), scores.parseability(), scores.writing(), scores.overall(),
                issuesJson, model, pipelineVersion, Status.PROCESSING, Status.COMPLETED);
    }

    /** PROCESSING → FAILED with an error code. */
    default int failProcessing(UUID id, String errorCode) {
        return markFailed(id, errorCode, Status.PROCESSING, Status.FAILED);
    }

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Analysis a set a.status = :to, a.startedAt = :startedAt
            where a.id = :id and a.status = :from
            """)
    int markProcessing(@Param("id") UUID id, @Param("startedAt") Instant startedAt,
            @Param("from") Status from, @Param("to") Status to);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Analysis a set a.status = :to,
                a.scoreFormatting = :formatting, a.scoreParseability = :parseability,
                a.scoreWriting = :writing, a.scoreOverall = :overall,
                a.issues = :issues, a.model = :model, a.pipelineVersion = :pipelineVersion
            where a.id = :id and a.status = :from
            """)
    int storeResult(@Param("id") UUID id, @Param("formatting") int formatting,
            @Param("parseability") int parseability, @Param("writing") int writing,
            @Param("overall") int overall, @Param("issues") String issues, @Param("model") String model,
            @Param("pipelineVersion") String pipelineVersion,
            @Param("from") Status from, @Param("to") Status to);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Analysis a set a.status = :to, a.errorCode = :errorCode
            where a.id = :id and a.status = :from
            """)
    int markFailed(@Param("id") UUID id, @Param("errorCode") String errorCode,
            @Param("from") Status from, @Param("to") Status to);
}
