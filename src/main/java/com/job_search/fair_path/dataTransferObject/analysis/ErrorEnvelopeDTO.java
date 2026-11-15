package com.job_search.fair_path.dataTransferObject.analysis;

import java.time.Instant;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * The shared error envelope, on internal endpoints and on Spring's own
 * rejections. retryAt (UTC) is set only when waiting is the fix, such as the Cooldown.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ErrorEnvelopeDTO(String correlationId, String errorCode, boolean retryable, Instant retryAt) {

    public ErrorEnvelopeDTO(String correlationId, String errorCode, boolean retryable) {
        this(correlationId, errorCode, retryable, null);
    }
}
