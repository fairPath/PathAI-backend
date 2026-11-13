package com.job_search.fair_path.dataTransferObject.analysis;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** The user's own details, sent as a redaction deny-list only. Each field is nullable. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KnownPiiDTO(String fullName, String email, String phone) {
}
