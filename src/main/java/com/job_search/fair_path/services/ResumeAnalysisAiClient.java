package com.job_search.fair_path.services;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeResponseDTO;

/** Thin Analyze client over the shared aiServiceRestClient. */
@Component
public class ResumeAnalysisAiClient {

    private final RestClient aiServiceRestClient;

    public ResumeAnalysisAiClient(@Qualifier("aiServiceRestClient") RestClient aiServiceRestClient) {
        this.aiServiceRestClient = aiServiceRestClient;
    }

    public AnalyzeResponseDTO analyze(AnalyzeRequestDTO request) {
        return aiServiceRestClient.post()
                .uri("/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AnalyzeResponseDTO.class);
    }
}
