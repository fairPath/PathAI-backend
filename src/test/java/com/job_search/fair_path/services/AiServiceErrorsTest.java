package com.job_search.fair_path.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.exceptions.AiServiceException;

class AiServiceErrorsTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"false\"", "0", "{}"})
    void malformedRetryableDoesNotOverrideTransientStatus(String retryable) throws Exception {
        var response = new MockClientHttpResponse(("{\"correlation_id\":\"" + ID
                + "\",\"error_code\":\"AI_UNAVAILABLE\",\"retryable\":" + retryable + "}")
                .getBytes(StandardCharsets.UTF_8), HttpStatus.SERVICE_UNAVAILABLE);
        var error = AiServiceErrors.decode(response, new ObjectMapper(), 8192);
        assertThat(error.hasEnvelope()).isFalse();
        assertThat(AiServiceErrors.isUnreachable(error)).isTrue();
        assertThat(AiServiceErrors.comparisonFailure(error, ID).retryable()).isTrue();
    }

    @Test
    void typedTerminalFailureOverridesTransientStatusAndPreservesRetryAfter() throws Exception {
        var response = new MockClientHttpResponse(("{\"correlation_id\":\"" + ID
                + "\",\"error_code\":\"AI_CONFIGURATION_ERROR\",\"retryable\":false}")
                .getBytes(StandardCharsets.UTF_8), HttpStatus.BAD_GATEWAY);
        response.getHeaders().set("Retry-After", "60");
        var error = AiServiceErrors.decode(response, new ObjectMapper(), 8192);
        assertThat(error.hasEnvelope()).isTrue();
        assertThat(error.retryAfterSeconds()).isEqualTo(60);
        assertThat(AiServiceErrors.comparisonFailure(error, ID).retryable()).isFalse();
    }

    @Test
    void nestedResponseBodyTimeoutHasTheSameClassificationForBothFeatures() {
        var error = new RestClientException("Synthetic wrapper", new SocketTimeoutException("Synthetic timeout"));
        assertThat(AiServiceErrors.analysisErrorCode(error)).isEqualTo("ANALYSIS_TIMED_OUT");
        assertThat(AiServiceErrors.comparisonFailure(error, ID).errorCode()).isEqualTo("AI_TIMED_OUT");
        assertThat(AiServiceErrors.comparisonFailure(error, ID).retryable()).isTrue();
    }

    @Test
    void invalidModelJsonIsNotMistakenForATransportFailure() {
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> new ObjectMapper().readTree("not JSON"));
        assertThat(AiServiceErrors.isTransientTransport(error)).isFalse();
        assertThat(AiServiceErrors.comparisonFailure(error, ID).retryable()).isFalse();
        assertThat(AiServiceErrors.comparisonFailure(error, ID).errorCode()).isEqualTo("INVALID_AI_RESPONSE");
    }
    @Test
    void analysisRetainsItsExistingRetryEligibilityAndAttemptLimit() {
        var refused = new RestClientException("Synthetic wrapper", new java.net.ConnectException("Synthetic refusal"));
        assertThat(AiServiceErrors.canRetryAnalysis(refused, 1, 2)).isTrue();
        assertThat(AiServiceErrors.canRetryAnalysis(refused, 2, 2)).isFalse();
        assertThat(AiServiceErrors.canRetryAnalysis(
                new RestClientException("Synthetic wrapper", new SocketTimeoutException("Synthetic timeout")), 1, 2)).isFalse();
    }

    @Test
    void temporaryStorageFailuresAreDistinctFromPermanentDatabaseErrors() {
        assertThat(AiServiceErrors.isTransientDatabase(new org.springframework.dao.CannotAcquireLockException("Synthetic lock timeout"))).isTrue();
        assertThat(AiServiceErrors.isTransientDatabase(new org.springframework.jdbc.BadSqlGrammarException(
                "Synthetic query", "SELECT synthetic", new java.sql.SQLException("Synthetic invalid column")))).isFalse();
    }

    @Test
    void oversizedErrorBodyFallsBackToItsHttpStatusWithoutReadingItAll() throws Exception {
        var response = new MockClientHttpResponse(("{\"detail\":\"" + "x".repeat(1024) + "\"}")
                .getBytes(StandardCharsets.UTF_8), HttpStatus.SERVICE_UNAVAILABLE);
        var error = AiServiceErrors.decode(response, new ObjectMapper(), 128);
        assertThat(error.hasEnvelope()).isFalse();
        assertThat(response.getBody().read()).isEqualTo((int) 'x');
        assertThat(AiServiceErrors.comparisonFailure(error, ID).retryable()).isTrue();
    }

    @Test
    void analysisFollowsTheAiServicesRetryableFlagWithoutAListEdit() {
        var permanent = new AiServiceException(502, ID.toString(), "AI_CONFIGURATION_ERROR", false, 0);
        var invalid = new AiServiceException(502, ID.toString(), "INVALID_AI_REQUEST", false, 0);
        var transientFailure = new AiServiceException(503, ID.toString(), "AI_UNAVAILABLE", true, 0);
        assertThat(AiServiceErrors.analysisRetryable(permanent, "AI_CONFIGURATION_ERROR")).isFalse();
        assertThat(AiServiceErrors.analysisRetryable(invalid, "INVALID_AI_REQUEST")).isFalse();
        assertThat(AiServiceErrors.analysisRetryable(transientFailure, "AI_UNAVAILABLE")).isTrue();
    }

    @Test
    void analysisKeepsItsPermanentCodesEvenIfTheFlagSaysRetry() {
        var wrongFlag = new AiServiceException(422, ID.toString(), "FILE_UNREADABLE", true, 0);
        assertThat(AiServiceErrors.analysisRetryable(wrongFlag, "FILE_UNREADABLE")).isFalse();
    }

    @Test
    void aSpringWrittenCodeIgnoresTheFlagOfTheErrorItReplaced() {
        // A 401 from the AI service is stored as INTERNAL_ERROR (our token, not the user's file).
        var rejectedToken = new AiServiceException(401, ID.toString(), "AI_AUTHENTICATION_FAILED", false, 0);
        assertThat(AiServiceErrors.analysisRetryable(rejectedToken, "INTERNAL_ERROR")).isTrue();
        assertThat(AiServiceErrors.analysisRetryable(new SocketTimeoutException("slow"), "ANALYSIS_TIMED_OUT")).isTrue();
        assertThat(AiServiceErrors.analysisRetryable(new RuntimeException("boom"), "INTERNAL_ERROR")).isTrue();
    }
}
