package com.job_search.fair_path.services;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.exceptions.AiServiceException;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeResponseDTO;
import com.job_search.fair_path.dataTransferObject.analysis.ScoresDTO;
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

    private static final Set<String> SEVERITIES = Set.of("low", "medium", "high");

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    private final AnalysisRepository analysisRepository;
    private final ResumeAnalysisAiClient aiClient;
    private final ObjectMapper objectMapper;
    private final AiRequestExecution execution;
    private final AnalysisService analysisService;

    public AnalysisRunner(AnalysisRepository analysisRepository, ResumeAnalysisAiClient aiClient,
            ObjectMapper objectMapper, AiRequestExecution execution, AnalysisService analysisService) {
        this.analysisRepository = analysisRepository;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.execution = execution;
        this.analysisService = analysisService;
    }

    // The shared @Async executor returns immediately, and the guarded
    // updates below each run in their own transaction instead of joining the
    // one that has already committed.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnalysisRequested(AnalysisRequestedEvent event) {
        UUID id = event.request().correlationId();
        try {
            execution.executeAnalysis(() -> run(event.request()));
        } catch (TaskRejectedException busy) {
            // The queue is full. The row is committed, so fail it for the next poll; never throw after commit.
            log.warn("Analysis queue full, failing the row correlation_id={}", id);
            try {
                if (analysisRepository.failPending(id, AnalysisErrorCodes.AI_BUSY) == 0) {
                    log.warn("Busy failure discarded, the row is no longer PENDING correlation_id={}", id);
                }
            } catch (RuntimeException e) {
                // e.g. the database is down; the sweep times the row out later.
                log.error("Could not mark the Analysis busy correlation_id={} error={}", id,
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Never throws: nothing above this method would catch an exception on the
     * async thread, so anything unexpected (e.g. a database error while
     * storing) fails the row on a best-effort basis instead of stranding it.
     */
    public void run(AnalyzeRequestDTO request) {
        UUID id = request.correlationId();
        try {
            process(id, request);
        } catch (RuntimeException e) {
            // Class name only: exception messages can carry row data.
            log.error("Analysis runner failed correlation_id={} error={}", id, e.getClass().getSimpleName());
            try {
                fail(id, AnalysisErrorCodes.INTERNAL_ERROR);
            } catch (RuntimeException again) {
                // e.g. the database is down; the sweep times the row out later.
                log.error("Could not mark the Analysis failed correlation_id={} error={}", id,
                        again.getClass().getSimpleName());
            }
        }
    }

    private void process(UUID id, AnalyzeRequestDTO request) {
        if (analysisRepository.claim(id, Instant.now()) == 0) {
            log.warn("Stale claim, not calling the AI service correlation_id={}", id);
            return;
        }

        AnalyzeResponseDTO response;
        try {
            response = execution.call(id, () -> aiClient.analyze(request));
        } catch (RuntimeException e) {
            // The envelope belongs to another Analysis: leave the row, like a mismatched 200.
            if (e instanceof AiServiceException ex && ex.correlationId() != null
                    && !id.toString().equals(ex.correlationId())) {
                log.error("AI-service error has a mismatched correlation_id, row left alone correlation_id={}", id);
                return;
            }
            if (e instanceof AiServiceException ex && ex.httpStatus() == 401)
                log.error("AI service rejected the internal token correlation_id={}", id);
            String errorCode = AiServiceErrors.analysisErrorCode(e);
            log.warn("AI-service call failed correlation_id={} error={} error_code={}", id,
                    e.getClass().getSimpleName(), errorCode);
            fail(id, errorCode);
            return;
        }

        // Left PROCESSING: the sweep times the row out later.
        if (response == null || !id.toString().equals(response.correlationId())) {
            log.error("AI-service response has a mismatched correlation_id, not stored correlation_id={}", id);
            return;
        }

        String issuesJson = isWellFormed(response) ? issuesJson(response) : null;
        if (issuesJson == null) {
            log.warn("AI-service response is malformed, not stored correlation_id={}", id);
            fail(id, AnalysisErrorCodes.INTERNAL_ERROR);
            return;
        }

        // The transition and its Usage record commit together, or not at all.
        int rows = analysisService.completeAnalysis(id, response.scores(), issuesJson, response.model(),
                response.pipelineVersion());
        if (rows == 0) {
            log.warn("Late result discarded correlation_id={}", id);
        }
    }

    private void fail(UUID id, String errorCode) {
        if (analysisRepository.failProcessing(id, errorCode) == 0) {
            log.warn("Late failure discarded correlation_id={}", id);
        }
    }

    /**
     * Every Score present and within 0–100, and every Issue carrying the
     * fields the results view relies on. Snippet and Line fields may be null.
     */
    static boolean isWellFormed(AnalyzeResponseDTO response) {
        ScoresDTO scores = response.scores();
        if (scores == null || response.issues() == null) {
            return false;
        }
        boolean scoresValid = Stream.of(scores.formatting(), scores.parseability(), scores.writing(),
                scores.overall()).allMatch(score -> score != null && score >= 0 && score <= 100);
        boolean issuesValid = response.issues().stream().allMatch(issue -> issue != null
                && issue.issueId() != null && issue.issueType() != null && issue.category() != null
                && SEVERITIES.contains(issue.severity())
                && issue.description() != null && issue.suggestion() != null);
        return scoresValid && issuesValid;
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
