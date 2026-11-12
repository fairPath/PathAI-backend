package com.job_search.fair_path.dataTransferObject.analysis;

import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Spring → AI service Analyze request. correlation_id is the Analysis id. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnalyzeRequestDTO(UUID correlationId, String s3Key, KnownPiiDTO knownPii) {
}
