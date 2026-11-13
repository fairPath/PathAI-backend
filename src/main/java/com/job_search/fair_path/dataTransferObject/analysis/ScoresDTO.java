package com.job_search.fair_path.dataTransferObject.analysis;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Integers 0–100, computed by the AI service's Scorer. Boxed so a missing
 * field decodes as null (and is rejected) instead of silently becoming 0.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScoresDTO(Integer formatting, Integer parseability, Integer writing, Integer overall) {
}
