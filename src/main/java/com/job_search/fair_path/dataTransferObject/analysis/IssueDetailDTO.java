package com.job_search.fair_path.dataTransferObject.analysis;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** One Issue. Snippet and Line fields are all null for a document-level Issue. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IssueDetailDTO(
        String issueId,
        String issueType,
        String category,
        String source,
        String severity,
        String snippet,
        Integer lineId,
        String lineText,
        String section,
        String lineKind,
        String description,
        String suggestion) {
}
