package com.job_search.fair_path.services;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.job_search.fair_path.dataTransferObject.analysis.ScoresDTO;
import com.job_search.fair_path.entity.UsageRecord;
import com.job_search.fair_path.repository.AnalysisRepository;
import com.job_search.fair_path.repository.UsageRecordRepository;
import com.job_search.fair_path.repository.UserRepository;

/**
 * The PROCESSING → COMPLETED transition and its Usage record, in one
 * transaction. A separate bean, so the call from AnalysisRunner goes through
 * Spring's proxy and the transaction really starts. It stays short on purpose:
 * the AI call happens before it, never inside it.
 */
@Component
public class AnalysisCompletion {

    private final AnalysisRepository analysisRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public AnalysisCompletion(AnalysisRepository analysisRepository, UsageRecordRepository usageRecordRepository,
            UserRepository userRepository, Clock clock) {
        this.analysisRepository = analysisRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * Returns the rows the guarded update changed. The Usage record is
     * written only when that is 1, so a late result counts for nothing. If the
     * insert throws, the update rolls back with it.
     */
    @Transactional
    public int complete(UUID id, ScoresDTO scores, String issuesJson, String model, String pipelineVersion) {
        int rows = analysisRepository.complete(id, scores, issuesJson, model, pipelineVersion);
        if (rows == 1) {
            UUID userId = analysisRepository.findUserId(id);
            usageRecordRepository.save(new UsageRecord(userRepository.getReferenceById(userId),
                    UsageRecord.Kind.ANALYSIS_COMPLETED, clock.instant()));
        }
        return rows;
    }
}
