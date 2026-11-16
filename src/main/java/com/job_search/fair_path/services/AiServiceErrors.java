package com.job_search.fair_path.services;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.client.ClientHttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.exceptions.AiServiceException;
import com.job_search.fair_path.exceptions.ComparisonFailure;

/** Shared AI error decoding and retry classification. Never exposes response bodies. */
public final class AiServiceErrors {
    private static final Set<String> TERMINAL_ANALYSIS_CODES = Set.of("TEXT_NOT_EXTRACTABLE", "FILE_UNREADABLE");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
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

    public static boolean isTimeout(Throwable error) {
        return hasCause(error, SocketTimeoutException.class);
    }

    /** Analysis's existing manual-retry policy, shared with its DTO mapping. */
    public static boolean isRetryableAnalysisCode(String errorCode) {
        return errorCode == null || !TERMINAL_ANALYSIS_CODES.contains(errorCode);
    }

    /** Analysis's existing automatic-retry eligibility; execution keeps its configured delay. */
    public static boolean isUnreachable(Throwable error) {
        var ai = aiException(error);
        if (ai != null) return ai.httpStatus() == 503 && !ai.hasEnvelope();
        return hasCause(error, ConnectException.class);
    }

    /** Preserve the Analysis API's established outcome codes. */
    public static String analysisErrorCode(Throwable error) {
        var ai = aiException(error);
        if (ai != null) {
            if (ai.httpStatus() == 401) return AnalysisErrorCodes.INTERNAL_ERROR;
            return ai.hasEnvelope() ? ai.errorCode() : AnalysisErrorCodes.INTERNAL_ERROR;
        }
        return isTimeout(error) ? AnalysisErrorCodes.ANALYSIS_TIMED_OUT : AnalysisErrorCodes.INTERNAL_ERROR;
    }

    public static boolean hasMismatchedCorrelation(Throwable error, UUID id) {
        var ai = aiException(error);
        return ai != null && ai.correlationId() != null && !id.toString().equals(ai.correlationId());
    }

    private static AiServiceException aiException(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause())
            if (cause instanceof AiServiceException ai) return ai;
        return null;
    }

    public static ComparisonFailure comparisonFailure(Throwable error, UUID correlationId) {
        String code = analysisErrorCode(error);
        var failure = new ComparisonFailure(code, isRetryableAnalysisCode(code));
        failure.initCause(error);
        return failure;
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
