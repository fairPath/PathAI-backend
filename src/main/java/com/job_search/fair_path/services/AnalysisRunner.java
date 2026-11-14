package com.job_search.fair_path.services;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.config.AiServiceException;
import com.job_search.fair_path.config.AiServiceProperties;
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

    /** Walking skeleton: one generic code for every AI-service failure. */
    static final String GENERIC_FAILURE = "INTERNAL_ERROR";

    private static final Set<String> SEVERITIES = Set.of("low", "medium", "high");

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    private final AnalysisRepository analysisRepository;
    private final ResumeAnalysisAiClient aiClient;
    private final ObjectMapper objectMapper;
    private final AiServiceProperties properties;

    public AnalysisRunner(AnalysisRepository analysisRepository, ResumeAnalysisAiClient aiClient,
            ObjectMapper objectMapper, AiServiceProperties properties) {
        this.analysisRepository = analysisRepository;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    // @Async: the request thread returns 202 without waiting, and the guarded
    // updates below each run in their own transaction instead of joining the
    // one that has already committed.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnalysisRequested(AnalysisRequestedEvent event) {
        run(event.request());
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
                fail(id);
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
            response = callWithOneRetry(id, request);
        } catch (RuntimeException e) {
            // The envelope belongs to another Analysis: leave the row, like a mismatched 200.
            if (e instanceof AiServiceException ex && ex.correlationId() != null
                    && !id.toString().equals(ex.correlationId())) {
                log.error("AI-service error has a mismatched correlation_id, row left alone correlation_id={}", id);
                return;
            }
            String errorCode = errorCodeFor(id, e);
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
        fail(id, GENERIC_FAILURE);
    }

    private void fail(UUID id, String errorCode) {
        if (analysisRepository.failProcessing(id, errorCode) == 0) {
            log.warn("Late failure discarded correlation_id={}", id);
        }
    }

    /**
     * One automatic retry, only for failures that say the AI service wasn't
     * reachable (a refused connection, or a 503 with no envelope: a restart or
     * a proxy). The row stays PROCESSING during the wait; the sweep covers a
     * restart in the middle of it. Every other failure is final.
     */
    private AnalyzeResponseDTO callWithOneRetry(UUID id, AnalyzeRequestDTO request) {
        try {
            return aiClient.analyze(request);
        } catch (RuntimeException first) {
            if (!isUnreachable(first)) {
                throw first;
            }
            Duration delay = properties.retryDelay();
            log.warn("AI service unreachable, retrying once in {} correlation_id={}", delay, id);
            try {
                if (!delay.isZero()) {
                    Thread.sleep(delay.toMillis());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return aiClient.analyze(request);
        }
    }

    private static boolean isUnreachable(RuntimeException e) {
        if (e instanceof AiServiceException ex) {
            return ex.httpStatus() == 503 && !ex.hasEnvelope();
        }
        return hasCause(e, ConnectException.class);
    }

    /**
     * The stored error_code for a failed call (spec: Spring outcome mapping).
     * An envelope's code is kept, since the code alone decides retryability;
     * everything else is a Spring-written code.
     */
    private static String errorCodeFor(UUID id, RuntimeException e) {
        if (e instanceof AiServiceException ex) {
            if (ex.httpStatus() == 401) {
                log.error("AI service rejected the internal token (401): check FAIRPATH_AI_INTERNAL_TOKEN "
                        + "correlation_id={}", id);
                return AnalysisErrorCodes.INTERNAL_ERROR;
            }
            return ex.hasEnvelope() ? ex.errorCode() : AnalysisErrorCodes.INTERNAL_ERROR;
        }
        if (hasCause(e, SocketTimeoutException.class)) {
            return AnalysisErrorCodes.ANALYSIS_TIMED_OUT;
        }
        return AnalysisErrorCodes.INTERNAL_ERROR;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
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
