package com.job_search.fair_path.dataTransferObject.analysis;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Integers 0–100, computed by the AI service's Scorer. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScoresDTO(int formatting, int parseability, int writing, int overall) {
}
