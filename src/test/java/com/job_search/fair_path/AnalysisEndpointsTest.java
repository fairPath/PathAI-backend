package com.job_search.fair_path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.job_search.fair_path.support.AnalysisTestSupport;

/**
 * The Analyze flow at Spring's REST API: real Postgres, real security filter,
 * the AI service stubbed at the aiServiceRestClient HTTP boundary.
 */
class AnalysisEndpointsTest extends AnalysisTestSupport {

    @Test
    void analyzeReturns202ThenTheStoredResultIsPolledToCompleted() throws Exception {
        aiService.stubFor(post(urlEqualTo("/resume"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(aiResponseEchoingCorrelationId())
                        .withTransformers("response-template")));

        String accepted = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/resumes/analyze")
                                .header("Authorization", bearerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String analysisId = JsonPath.read(accepted, "$.analysis_id");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mockMvc
                .perform(get("/resumes/analysis").header("Authorization", bearerToken))
                .andExpect(jsonPath("$.status").value("COMPLETED")));

        mockMvc.perform(get("/resumes/analysis").header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis_id").value(analysisId))
                .andExpect(jsonPath("$.error_code").value(nullValue()))
                .andExpect(jsonPath("$.scores.formatting").value(0))
                .andExpect(jsonPath("$.scores.parseability").value(100))
                .andExpect(jsonPath("$.scores.writing").value(100))
                .andExpect(jsonPath("$.scores.overall").value(67))
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].issue_id").value("fmt-001"))
                .andExpect(jsonPath("$.issues[0].issue_type").value("MISSING_CONTACT"))
                .andExpect(jsonPath("$.issues[0].severity").value("high"))
                .andExpect(jsonPath("$.issues[0].snippet").value(nullValue()))
                .andExpect(jsonPath("$.issues[0].line_id").value(nullValue()))
                .andExpect(jsonPath("$.model").value(nullValue()))
                .andExpect(jsonPath("$.pipeline_version").value("0.1.0"));

        // The Analysis row's id is the correlation_id; known_pii comes from the user.
        aiService.verify(1, postRequestedFor(urlEqualTo("/resume"))
                .withHeader("X-Internal-Token", equalTo(AI_SERVICE_TOKEN))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(equalToJson("""
                        {
                          "correlation_id": "%s",
                          "s3_key": "%s",
                          "known_pii": { "full_name": "Test User", "email": "%s", "phone": null }
                        }
                        """.formatted(analysisId, resume.getS3Key(), user.getEmail()))));
    }

    private static String aiResponseEchoingCorrelationId() {
        return """
                {
                  "correlation_id": "{{jsonPath request.body '$.correlation_id'}}",
                  "scores": { "formatting": 0, "parseability": 100, "writing": 100, "overall": 67 },
                  "issues": [ %s ],
                  "model": null,
                  "pipeline_version": "0.1.0"
                }
                """.formatted(MISSING_CONTACT_ISSUE_JSON);
    }
}
