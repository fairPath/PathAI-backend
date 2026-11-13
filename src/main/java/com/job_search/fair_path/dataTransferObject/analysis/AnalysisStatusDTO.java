package com.job_search.fair_path.dataTransferObject.analysis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.job_search.fair_path.entity.Analysis;

/**
 * What the frontend polls. scores and issues are null until COMPLETED;
 * error_code is set only when FAILED.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnalysisStatusDTO(
        UUID analysisId,
        Analysis.Status status,
        Instant createdAt,
        String errorCode,
        ScoresDTO scores,
        List<IssueDetailDTO> issues,
        String model,
        String pipelineVersion) {
}
