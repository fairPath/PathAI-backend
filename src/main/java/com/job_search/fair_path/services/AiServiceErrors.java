package com.job_search.fair_path.services;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.exceptions.AiServiceException;
import com.job_search.fair_path.exceptions.ComparisonFailure;

/** Shared AI error decoding and retry classification. Never exposes response bodies. */
public final class AiServiceErrors {
    private static final Set<String> TERMINAL_ANALYSIS_CODES = Set.of("TEXT_NOT_EXTRACTABLE", "FILE_UNREADABLE");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Set<String> COMPARISON_CODES = Set.of(
            "AI_AUTHENTICATION_FAILED", "INVALID_COMPARISON_REQUEST", "RESUME_UNREADABLE",
            "RESUME_UNAVAILABLE", "STORAGE_CONFIGURATION_ERROR", "AI_CONFIGURATION_ERROR",
            "UPSTREAM_RATE_LIMITED", "AI_UNAVAILABLE", "AI_TIMED_OUT", "INVALID_AI_RESPONSE",
            "INTERNAL_ERROR");

    private AiServiceErrors() {}

    /** A malformed envelope never changes the classification implied by its HTTP status. */
    public static AiServiceException decode(ClientHttpResponse response, ObjectMapper mapper, int maxErrorBodyBytes) throws IOException {
        int status = response.getStatusCode().value();
        long retryAfter = retryAfterSeconds(response.getHeaders().getFirst("Retry-After"));
        try {
            JsonNode body = mapper.readTree(response.getBody().readNBytes(maxErrorBodyBytes));
            JsonNode correlation = body == null ? null : body.get("correlation_id");
            JsonNode code = body == null ? null : body.get("error_code");
            JsonNode retryable = body == null ? null : body.get("retryable");
            if (correlation != null && correlation.isTextual()
                    && code != null && code.isTextual() && ERROR_CODE.matcher(code.asText()).matches()
                    && retryable != null && retryable.isBoolean()) {
                return new AiServiceException(status, correlation.asText(), code.asText(), retryable.asBoolean(), retryAfter);
            }
        } catch (IOException | RuntimeException ignored) {
            // Error bodies and exception messages can contain resume text.
        }
        return new AiServiceException(status, null, null, false, retryAfter);
    }

    public static boolean isTransientStatus(int status) {
        return status == 408 || status == 429 || status == 502 || status == 503 || status == 504;
    }

    public static boolean isTimeout(Throwable error) {
        return hasCause(error, SocketTimeoutException.class);
    }

    public static boolean isTransientTransport(Throwable error) {
        return isTimeout(error) || hasCause(error, SocketException.class)
                || hasCause(error, UnknownHostException.class) || error instanceof ResourceAccessException;
    }

    public static boolean isTransientDatabase(Throwable error) {
        return hasCause(error, TransientDataAccessException.class)
                || hasCause(error, RecoverableDataAccessException.class)
                || hasCause(error, DataAccessResourceFailureException.class);
    }

    /** Analysis's existing manual-retry policy, shared with its DTO mapping. */
    public static boolean isRetryableAnalysisCode(String errorCode) {
        return errorCode == null || !TERMINAL_ANALYSIS_CODES.contains(errorCode);
    }

    public static boolean canRetryAnalysis(Throwable error, int attempt, int maxAttempts) {
        return attempt < maxAttempts && isUnreachable(error);
    }

    /** Analysis's existing automatic-retry eligibility; execution keeps its configured delay. */
    public static boolean isUnreachable(Throwable error) {
        if (error instanceof AiServiceException ai) return ai.httpStatus() == 503 && !ai.hasEnvelope();
        return hasCause(error, ConnectException.class);
    }

    /** Preserve the Analysis API's established outcome codes. */
    public static String analysisErrorCode(Throwable error) {
        if (error instanceof AiServiceException ai) {
            if (ai.httpStatus() == 401) return AnalysisErrorCodes.INTERNAL_ERROR;
            return ai.hasEnvelope() ? ai.errorCode() : AnalysisErrorCodes.INTERNAL_ERROR;
        }
        return isTimeout(error) ? AnalysisErrorCodes.ANALYSIS_TIMED_OUT : AnalysisErrorCodes.INTERNAL_ERROR;
    }

    public static ComparisonFailure comparisonFailure(Throwable error, UUID correlationId) {
        if (error instanceof AiServiceException ai) {
            int status = ai.httpStatus();
            boolean retryable = isTransientStatus(status);
            String code = status == 401 || status == 403 ? "AI_AUTHENTICATION_FAILED"
                    : status == 422 ? "INVALID_COMPARISON_REQUEST"
                    : status == 429 ? "UPSTREAM_RATE_LIMITED"
                    : retryable ? "AI_UNAVAILABLE" : "AI_REQUEST_REJECTED";
            if (correlationId.toString().equals(ai.correlationId()) && ai.hasEnvelope() && COMPARISON_CODES.contains(ai.errorCode())) {
                code = ai.errorCode();
                retryable = retryable && ai.retryable();
            }
            return new ComparisonFailure(code, retryable, ai.retryAfterSeconds());
        }
        if (isTransientTransport(error)) return new ComparisonFailure(isTimeout(error) ? "AI_TIMED_OUT" : "AI_UNAVAILABLE", true);
        return new ComparisonFailure("INVALID_AI_RESPONSE", false);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }

    private static long retryAfterSeconds(String value) {
        if (value == null) return 0;
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            try {
                return Math.max(0, Duration.between(Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds());
            } catch (java.time.DateTimeException invalid) {
                return 0;
            }
        }
    }
}
