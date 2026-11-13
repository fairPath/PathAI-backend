package com.job_search.fair_path.dataTransferObject.analysis;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * AI service → Spring Analyze response. correlation_id is kept as a string so
 * a malformed echo is a mismatch, not a decoding failure.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnalyzeResponseDTO(
        String correlationId,
        ScoresDTO scores,
        List<IssueDetailDTO> issues,
        String model,
        String pipelineVersion) {
}
