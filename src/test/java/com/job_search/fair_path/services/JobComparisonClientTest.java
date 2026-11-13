package com.job_search.fair_path.services;

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
             "relevant_experience": "Five years of backend work", "other_gaps": []}
            """;

    @Autowired
    private JobComparisonClient client;

    private final JobComparisonQueue.Task task = new JobComparisonQueue.Task(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "job-1", "snapshots/resume.pdf", "Java and Go services", 1);

    private void stubComparison(int status, String body) {
        aiService.stubFor(post(urlEqualTo("/job-comparison"))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void postsTheComparisonRequestThroughTheSharedClientAndParsesTheResult() throws Exception {
        stubComparison(200, VALID_RESULT);

        var result = client.compare(task);

        assertThat(result.matchScore()).isEqualTo(85);
        assertThat(result.matchedSkills()).containsExactly("Java");
        assertThat(result.missingSkills()).containsExactly("Go");
        assertThat(result.relevantExperience()).isEqualTo("Five years of backend work");
        assertThat(result.otherGaps()).isEqualTo(List.of());
        aiService.verify(1, postRequestedFor(urlEqualTo("/job-comparison"))
                .withHeader("X-Internal-Token", equalTo(AI_SERVICE_TOKEN))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(equalToJson("""
                        {"job_id": "job-1", "s3_key": "snapshots/resume.pdf",
                         "job_description": "Java and Go services"}
                        """)));
    }

    @Test
    void serverErrorFailsWithTheStatusOnlyAndNeverTheResponseBody() {
        stubComparison(500, "{\"detail\": \"resume text that must not reach logs\"}");

        assertThatThrownBy(() -> client.compare(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI service returned HTTP 500")
                .hasMessageNotContaining("resume text");
    }

    @Test
    void unauthorizedFailsWithTheStatusOnly() {
        stubComparison(401, "{\"detail\": \"Invalid authentication credentials\"}");

        assertThatThrownBy(() -> client.compare(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI service returned HTTP 401");
    }

    @Test
    void scoreOutsideZeroToOneHundredIsRejected() {
        stubComparison(200, VALID_RESULT.replace("85", "101"));

        assertThatThrownBy(() -> client.compare(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid comparison result");
    }

    @Test
    void fractionalScoreIsRejectedRatherThanTruncated() {
        stubComparison(200, VALID_RESULT.replace("85", "85.5"));

        assertThatThrownBy(() -> client.compare(task)).isInstanceOf(Exception.class);
    }
}
