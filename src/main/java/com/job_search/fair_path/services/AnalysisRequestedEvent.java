package com.job_search.fair_path.services;

import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;

/** Published when a PENDING Analysis row is inserted; handled after commit. */
public record AnalysisRequestedEvent(AnalyzeRequestDTO request) {
}
