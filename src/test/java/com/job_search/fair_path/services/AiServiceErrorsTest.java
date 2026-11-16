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
    void sharedAnalysisCodePolicyPreservesRetryHeaderMetadata() throws Exception {
        var response = new MockClientHttpResponse(("{\"correlation_id\":\"" + ID
                + "\",\"error_code\":\"AI_CONFIGURATION_ERROR\",\"retryable\":false}")
                .getBytes(StandardCharsets.UTF_8), HttpStatus.BAD_GATEWAY);
        response.getHeaders().set("Retry-After", "60");
        var error = AiServiceErrors.decode(response, new ObjectMapper(), 8192);
        assertThat(error.hasEnvelope()).isTrue();
        assertThat(error.retryAfterSeconds()).isEqualTo(60);
        assertThat(AiServiceErrors.comparisonFailure(error, ID).retryable()).isTrue();
    }

    @Test
    void nestedResponseBodyTimeoutHasTheSameClassificationForBothFeatures() {
        var error = new RestClientException("Synthetic wrapper", new SocketTimeoutException("Synthetic timeout"));
        assertThat(AiServiceErrors.analysisErrorCode(error)).isEqualTo("ANALYSIS_TIMED_OUT");
        assertThat(AiServiceErrors.comparisonFailure(error, ID).errorCode()).isEqualTo("ANALYSIS_TIMED_OUT");
        assertThat(AiServiceErrors.comparisonFailure(error, ID).retryable()).isTrue();
    }

    @Test
    void invalidModelJsonUsesOriginalAnalysisManualRetryPolicy() {
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> new ObjectMapper().readTree("not JSON"));
        assertThat(AiServiceErrors.comparisonFailure(error, ID).retryable()).isTrue();
        assertThat(AiServiceErrors.comparisonFailure(error, ID).errorCode()).isEqualTo("INTERNAL_ERROR");
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

}
