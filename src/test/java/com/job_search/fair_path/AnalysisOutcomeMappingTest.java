package com.job_search.fair_path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;
import com.job_search.fair_path.dataTransferObject.analysis.KnownPiiDTO;
import com.job_search.fair_path.entity.Analysis;
import com.job_search.fair_path.repository.AnalysisRepository;
import com.job_search.fair_path.services.AnalysisRunner;
import com.job_search.fair_path.support.AnalysisTestSupport;

/**
 * The spec's "Spring outcome mapping" at the HTTP boundary, against the
 * stubbed AI service. Outcomes that need a dead socket (a refused connection,
 * a read timeout) are in AnalysisRunnerTransportFailuresTest.
 */
class AnalysisOutcomeMappingTest extends AnalysisTestSupport {

    @Autowired
    private AnalysisRunner runner;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Test
    void extraction422FailsWithItsCodeAndIsNotRetryable() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(envelope(422, id.toString(),
                "TEXT_NOT_EXTRACTABLE", false)));

        runner.run(requestFor(id));

        assertFailed(id, "TEXT_NOT_EXTRACTABLE", false);
        aiService.verify(1, postRequestedFor(urlEqualTo("/resume")));
    }

    @Test
    void unreadableFile422FailsWithItsCodeAndIsNotRetryable() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(envelope(422, id.toString(),
                "FILE_UNREADABLE", false)));

        runner.run(requestFor(id));

        assertFailed(id, "FILE_UNREADABLE", false);
    }

    @Test
    void a422WithoutAnEnvelopeIsAnInternalError() throws Exception {
        UUID id = insertPending();
        // What FastAPI sends when it rejects our request body: our bug, not the user's file.
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json").withBody("{\"detail\": []}")));

        runner.run(requestFor(id));

        assertFailed(id, "INTERNAL_ERROR", true);
    }

    @Test
    void a502WithAnEnvelopeFailsRetryableWithoutAutoRetry() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(envelope(502, id.toString(),
                "LLM_UNAVAILABLE", true)));

        runner.run(requestFor(id));

        assertFailed(id, "LLM_UNAVAILABLE", true);
        aiService.verify(1, postRequestedFor(urlEqualTo("/resume")));
    }

    @Test
    void a504WithAnEnvelopeFailsRetryableWithoutAutoRetry() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(envelope(504, id.toString(),
                "LLM_TIMEOUT", true)));

        runner.run(requestFor(id));

        assertFailed(id, "LLM_TIMEOUT", true);
        aiService.verify(1, postRequestedFor(urlEqualTo("/resume")));
    }

    @Test
    void a500FailsRetryableWithoutAutoRetry() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(aResponse().withStatus(500)
                .withBody("Traceback: Jane Testperson")));

        runner.run(requestFor(id));

        assertFailed(id, "INTERNAL_ERROR", true);
        aiService.verify(1, postRequestedFor(urlEqualTo("/resume")));
    }

    @Test
    void a503WithoutAnEnvelopeIsRetriedOnceAndCanSucceed() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).inScenario("restart")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("<html>Service Unavailable</html>"))
                .willSetStateTo("up"));
        aiService.stubFor(post(urlEqualTo("/resume")).inScenario("restart")
                .whenScenarioStateIs("up")
                .willReturn(okResult(id.toString())));

        runner.run(requestFor(id));

        pollLatest().andExpect(jsonPath("$.status").value("COMPLETED"));
        aiService.verify(2, postRequestedFor(urlEqualTo("/resume")));
    }

    @Test
    void a503WithoutAnEnvelopeTwiceFailsAfterOneRetry() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(aResponse().withStatus(503)));

        runner.run(requestFor(id));

        assertFailed(id, "INTERNAL_ERROR", true);
        aiService.verify(2, postRequestedFor(urlEqualTo("/resume")));
    }

    @Test
    void a503WithAnIncompleteEnvelopeCountsAsHavingNone() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json").withBody("{\"error_code\": \"SOMETHING\"}")));

        runner.run(requestFor(id));

        assertFailed(id, "INTERNAL_ERROR", true);
        aiService.verify(2, postRequestedFor(urlEqualTo("/resume")));
    }

    @Test
    void anErrorCodeThatIsNotAnIdentifierIsNeverStored() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(envelope(502, id.toString(),
                "not an identifier " + "x".repeat(300), true)));

        runner.run(requestFor(id));

        assertFailed(id, "INTERNAL_ERROR", true);
    }

    @Test
    void aRetryableThatIsNotABooleanMakesTheEnvelopeInvalid() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(aResponse().withStatus(502)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"correlation_id\": \"%s\", \"error_code\": \"LLM_UNAVAILABLE\", \"retryable\": \"true\"}"
                        .formatted(id))));

        runner.run(requestFor(id));

        assertFailed(id, "INTERNAL_ERROR", true);
    }

    @Test
    void a401FailsWithInternalErrorAndIsNotRetried() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"detail\": \"Invalid or missing internal token\"}")));

        runner.run(requestFor(id));

        assertFailed(id, "INTERNAL_ERROR", true);
        aiService.verify(1, postRequestedFor(urlEqualTo("/resume")));
    }

    @Test
    void anErrorEnvelopeForAnotherAnalysisLeavesTheRowAlone() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(envelope(422,
                UUID.randomUUID().toString(), "TEXT_NOT_EXTRACTABLE", false)));

        runner.run(requestFor(id));

        pollLatest()
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.error_code").value(nullValue()))
                .andExpect(jsonPath("$.retryable").value(nullValue()));
    }

    @Test
    void retryableIsAbsentUntilTheAnalysisFails() throws Exception {
        UUID id = insertPending();
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(okResult(id.toString())));

        runner.run(requestFor(id));

        pollLatest()
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.retryable").value(nullValue()));
    }

    @Test
    void profileOnlyReportsAConfirmedResume() throws Exception {
        mockMvc.perform(get("/users/me").header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumeId").value(resume.getId().toString()));

        resume.setStatus(com.job_search.fair_path.entity.Resume.Status.PENDING);
        resumeRepository.save(resume);

        mockMvc.perform(get("/users/me").header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumeId").value(nullValue()));
    }

    private void assertFailed(UUID id, String errorCode, boolean retryable) throws Exception {
        pollLatest()
                .andExpect(jsonPath("$.analysis_id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error_code").value(errorCode))
                .andExpect(jsonPath("$.retryable").value(retryable))
                .andExpect(jsonPath("$.scores").value(nullValue()));
    }

    private static ResponseDefinitionBuilder envelope(int status, String correlationId, String errorCode,
            boolean retryable) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody("""
                        { "correlation_id": "%s", "error_code": "%s", "retryable": %s }
                        """.formatted(correlationId, errorCode, retryable));
    }

    private UUID insertPending() {
        Analysis analysis = new Analysis();
        analysis.setResume(resume);
        analysis.setUserId(user.getId());
        analysis.setStatus(Analysis.Status.PENDING);
        return analysisRepository.save(analysis).getId();
    }

    private AnalyzeRequestDTO requestFor(UUID id) {
        return new AnalyzeRequestDTO(id, resume.getS3Key(), new KnownPiiDTO("Test User", user.getEmail(), null));
    }

    private ResultActions pollLatest() throws Exception {
        return mockMvc.perform(get("/resumes/analysis").header("Authorization", bearerToken))
                .andExpect(status().isOk());
    }

    private static ResponseDefinitionBuilder okResult(String correlationId) {
        return aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "correlation_id": "%s",
                          "scores": { "formatting": 0, "parseability": 100, "writing": 100, "overall": 67 },
                          "issues": [ %s ],
                          "model": null,
                          "pipeline_version": "0.1.0"
                        }
                        """.formatted(correlationId, MISSING_CONTACT_ISSUE_JSON));
    }
}
