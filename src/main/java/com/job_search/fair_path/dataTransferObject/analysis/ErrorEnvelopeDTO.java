package com.job_search.fair_path.dataTransferObject.analysis;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** The shared error envelope, on internal endpoints and on Spring's own rejections. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ErrorEnvelopeDTO(String correlationId, String errorCode, boolean retryable) {
}
