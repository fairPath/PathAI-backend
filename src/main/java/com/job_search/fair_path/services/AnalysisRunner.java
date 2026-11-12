package com.job_search.fair_path.services;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeResponseDTO;
import com.job_search.fair_path.repository.AnalysisRepository;

/**
 * Runs an Analysis once its PENDING row has committed: claim it, call the AI
 * service, store the result. Every step is a guarded compare-and-set, so a
 * stale claim never calls the AI service and a late result is discarded.
 *
 * Logs carry the correlation_id only, never Resume text or response bodies.
 */
@Component
public class AnalysisRunner {

    /** Walking skeleton: one generic code for every AI-service failure. */
    static final String GENERIC_FAILURE = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    private final AnalysisRepository analysisRepository;
    private final ResumeAnalysisAiClient aiClient;
    private final ObjectMapper objectMapper;

    public AnalysisRunner(AnalysisRepository analysisRepository, ResumeAnalysisAiClient aiClient,
            ObjectMapper objectMapper) {
        this.analysisRepository = analysisRepository;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    // @Async: the request thread returns 202 without waiting, and the guarded
    // updates below each run in their own transaction instead of joining the
    // one that has already committed.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnalysisRequested(AnalysisRequestedEvent event) {
        run(event.request());
    }

    public void run(AnalyzeRequestDTO request) {
        UUID id = request.correlationId();

        if (analysisRepository.claim(id, Instant.now()) == 0) {
            log.warn("Stale claim, not calling the AI service correlation_id={}", id);
            return;
        }

        AnalyzeResponseDTO response;
        try {
            response = aiClient.analyze(request);
        } catch (RuntimeException e) {
            log.warn("AI-service call failed correlation_id={} error={}", id, e.getClass().getSimpleName());
            fail(id);
            return;
        }

        // Left PROCESSING: the sweep times the row out later.
        if (response == null || !id.toString().equals(response.correlationId())) {
            log.error("AI-service response has a mismatched correlation_id, not stored correlation_id={}", id);
            return;
        }

        String issuesJson = issuesJson(response);
        if (response.scores() == null || issuesJson == null) {
            log.warn("AI-service response is missing its scores or issues correlation_id={}", id);
            fail(id);
            return;
        }

        int rows = analysisRepository.complete(id, response.scores(), issuesJson, response.model(),
                response.pipelineVersion());
        if (rows == 0) {
            log.warn("Late result discarded correlation_id={}", id);
        }
    }

    private void fail(UUID id) {
        if (analysisRepository.failProcessing(id, GENERIC_FAILURE) == 0) {
            log.warn("Late failure discarded correlation_id={}", id);
        }
    }

    /** The Issues as JSON, or null when the response has none to store. */
    private String issuesJson(AnalyzeResponseDTO response) {
        if (response.issues() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(response.issues());
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
