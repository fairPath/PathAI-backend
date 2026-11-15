package com.job_search.fair_path.services;

import com.job_search.fair_path.exceptions.ComparisonFailure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.job_search.fair_path.support.IntegrationTestBase;

/**
 * JobComparisonClient over the shared aiServiceRestClient, against the WireMock
 * stub for the AI service: the request contract and how responses are handled.
 */
class JobComparisonClientTest extends IntegrationTestBase {

    private static final String VALID_RESULT = """
            {"match_score": 85, "matched_skills": ["Java"], "missing_skills": ["Go"],
             "relevant_experience": "Five years of backend work", "other_gaps": [],
             "skill_match_score": 90, "experience_match_score": 80, "resume_advice": "Highlight your relevant Java projects."}
            """;

    @Autowired
    private JobComparisonClient client;

    private final JobComparisonQueue.Task task = new JobComparisonQueue.Task(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "job-1", "resumes/active.pdf", "Java and Go services", 1, UUID.randomUUID(), java.time.Instant.now());

    private void stubComparison(int status, String body) {
        if (status == 200) body = body.replaceFirst("\\{", "{\"correlation_id\":\"" + task.requestId() + "\",");
        aiService.stubFor(post(urlEqualTo("/job-comparison"))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void postsTheComparisonRequestThroughTheSharedClientAndParsesTheResult() throws Exception {
        stubComparison(200, VALID_RESULT);

        var result = client.compare(task);

        assertThat(result.matchScore()).isEqualTo(85);
        assertThat(result.skillMatchScore()).isEqualTo(90);
        assertThat(result.experienceMatchScore()).isEqualTo(80);
        assertThat(result.resumeAdvice()).isEqualTo("Highlight your relevant Java projects.");
        assertThat(result.matchedSkills()).containsExactly("Java");
        assertThat(result.missingSkills()).containsExactly("Go");
        assertThat(result.relevantExperience()).isEqualTo("Five years of backend work");
        assertThat(result.otherGaps()).isEqualTo(List.of());
        aiService.verify(1, postRequestedFor(urlEqualTo("/job-comparison"))
                .withHeader("X-Internal-Token", equalTo(AI_SERVICE_TOKEN))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(equalToJson("""
                        {"correlation_id": "%s", "job_id": "job-1", "s3_key": "resumes/active.pdf",
                         "job_description": "Java and Go services"}
                        """.formatted(task.requestId()))));
    }

    @Test
    void serverErrorFailsWithTheStatusOnlyAndNeverTheResponseBody() {
        stubComparison(500, "{\"detail\": \"resume text that must not reach logs\"}");

        assertThatThrownBy(() -> client.compare(task))
                .isInstanceOf(ComparisonFailure.class)
                .hasMessage("AI_REQUEST_REJECTED")
                .hasMessageNotContaining("resume text");
    }

    @Test
    void unauthorizedFailsWithTheStatusOnly() {
        stubComparison(401, "{\"detail\": \"Invalid authentication credentials\"}");

        assertThatThrownBy(() -> client.compare(task))
                .isInstanceOf(ComparisonFailure.class)
                .hasMessage("AI_AUTHENTICATION_FAILED");
    }

    @Test
    void scoreOutsideZeroToOneHundredIsRejected() {
        stubComparison(200, VALID_RESULT.replace("85", "101"));

        assertThatThrownBy(() -> client.compare(task))
                .isInstanceOf(ComparisonFailure.class)
                .hasMessage("INVALID_AI_RESPONSE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 401, 403, 404, 405, 413, 415, 422, 500})
    void permanentStatusesAreNotRetried(int status) {
        stubComparison(status, "{}");
        org.junit.jupiter.api.Assertions.assertFalse(
            org.junit.jupiter.api.Assertions.assertThrows(ComparisonFailure.class, () -> client.compare(task)).retryable());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {408, 429, 502, 503, 504})
    void transientStatusesAreRetriable(int status) {
        stubComparison(status, "{}");
        org.junit.jupiter.api.Assertions.assertTrue(
            org.junit.jupiter.api.Assertions.assertThrows(ComparisonFailure.class, () -> client.compare(task)).retryable());
    }

    @Test
    void typedPermanentProviderConfigurationFailureOverrides502() {
        stubComparison(502, """
                {"correlation_id": "%s", "error_code": "AI_CONFIGURATION_ERROR", "retryable": false}
                """.formatted(task.requestId()));
        var failure = org.junit.jupiter.api.Assertions.assertThrows(ComparisonFailure.class, () -> client.compare(task));
        org.junit.jupiter.api.Assertions.assertFalse(failure.retryable());
        org.junit.jupiter.api.Assertions.assertEquals("AI_CONFIGURATION_ERROR", failure.errorCode());
    }

    @Test
    void rateLimitHonorsRetryAfter() {
        aiService.stubFor(post(urlEqualTo("/job-comparison"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "60").withBody("{}")));
        var failure = org.junit.jupiter.api.Assertions.assertThrows(ComparisonFailure.class, () -> client.compare(task));
        org.junit.jupiter.api.Assertions.assertTrue(failure.retryable());
        org.junit.jupiter.api.Assertions.assertEquals(60, failure.retryAfterSeconds());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"skill_match_score", "experience_match_score"})
    void invalidComponentScoreIsRejected(String field) {
        stubComparison(200, VALID_RESULT.replace("\"" + field + "\": " + (field.equals("skill_match_score") ? "90" : "80"), "\"" + field + "\": 101"));
        assertThatThrownBy(() -> client.compare(task))
                .isInstanceOf(ComparisonFailure.class).hasMessage("INVALID_AI_RESPONSE");
    }

    @Test
    void fractionalScoreIsRejectedRatherThanTruncated() {
        stubComparison(200, VALID_RESULT.replace("85", "85.5"));

        assertThatThrownBy(() -> client.compare(task)).isInstanceOf(Exception.class);
    }
}
