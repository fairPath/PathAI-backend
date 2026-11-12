package com.job_search.fair_path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;
import com.job_search.fair_path.dataTransferObject.analysis.KnownPiiDTO;
import com.job_search.fair_path.entity.Analysis;
import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.repository.AnalysisRepository;
import com.job_search.fair_path.services.AnalysisRunner;
import com.job_search.fair_path.support.AnalysisTestSupport;

/**
 * Guarded compare-and-set transitions and the walking skeleton's edge cases.
 * The runner is invoked directly (as the after-commit listener would) so each
 * test controls the row's state before and during the AI-service call.
 */
class AnalysisTransitionsTest extends AnalysisTestSupport {

    @Autowired
    private AnalysisRunner runner;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void staleClaimExitsBeforeCallingTheAiService() throws Exception {
        UUID id = insertAnalysis(Analysis.Status.FAILED);

        runner.run(requestFor(id));

        aiService.verify(0, anyRequestedFor(anyUrl()));
        pollLatest().andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void lateResultAfterTheRowWasFailedIsDiscarded() throws Exception {
        UUID id = insertAnalysis(Analysis.Status.PENDING);
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(okResult(id.toString())
                .withFixedDelay(2_000)));

        CompletableFuture<Void> inFlight = CompletableFuture.runAsync(() -> runner.run(requestFor(id)));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> pollLatest()
                .andExpect(jsonPath("$.status").value("PROCESSING")));
        // What the sweep will do to a stuck row, while the AI-service call is still running.
        jdbc.update("update analysis set status = 'FAILED', error_code = 'ANALYSIS_TIMED_OUT' where id = ?", id);
        inFlight.join();

        pollLatest()
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error_code").value("ANALYSIS_TIMED_OUT"))
                .andExpect(jsonPath("$.scores").value(nullValue()));
        Analysis row = analysisRepository.findById(id).orElseThrow();
        assertThat(row.getIssues()).isNull();
        assertThat(row.getPipelineVersion()).isNull();
    }

    @Test
    void responseWithAMismatchedCorrelationIdIsNotStored() throws Exception {
        UUID id = insertAnalysis(Analysis.Status.PENDING);
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(okResult(UUID.randomUUID().toString())));

        runner.run(requestFor(id));

        // Left in PROCESSING for the sweep to time out.
        pollLatest()
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.scores").value(nullValue()))
                .andExpect(jsonPath("$.pipeline_version").value(nullValue()));
    }

    @Test
    void aiServiceErrorFailsTheAnalysis() throws Exception {
        UUID id = insertAnalysis(Analysis.Status.PENDING);
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(aResponse().withStatus(500)));

        runner.run(requestFor(id));

        pollLatest()
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error_code").value("INTERNAL_ERROR"));
    }

    @Test
    void malformedSuccessResponseFailsTheAnalysisInsteadOfLeavingItProcessing() throws Exception {
        UUID id = insertAnalysis(Analysis.Status.PENDING);
        aiService.stubFor(post(urlEqualTo("/resume")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        { "correlation_id": "%s", "scores": null, "issues": [],
                          "model": null, "pipeline_version": "0.1.0" }
                        """.formatted(id))));

        runner.run(requestFor(id));

        pollLatest()
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error_code").value("INTERNAL_ERROR"));
    }

    @Test
    void analyzeWithoutAnActiveResumeIsRejected() throws Exception {
        resume.setStatus(Resume.Status.PENDING); // uploaded but never confirmed
        resumeRepository.save(resume);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/resumes/analyze").header("Authorization", bearerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error_code").value("RESUME_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false));

        aiService.verify(0, anyRequestedFor(anyUrl()));
        mockMvc.perform(get("/resumes/analysis").header("Authorization", bearerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void pollingBeforeAnyAnalysisReturnsNoContent() throws Exception {
        mockMvc.perform(get("/resumes/analysis").header("Authorization", bearerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletingTheResumeDeletesItsAnalyses() throws Exception {
        insertAnalysis(Analysis.Status.COMPLETED);

        mockMvc.perform(delete("/resumes/delete").header("Authorization", bearerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/resumes/analysis").header("Authorization", bearerToken))
                .andExpect(status().isNoContent());
    }

    /** Fixture: an Analysis row already in the given status. */
    private UUID insertAnalysis(Analysis.Status status) {
        Analysis analysis = new Analysis();
        analysis.setResume(resume);
        analysis.setUserId(user.getId());
        analysis.setStatus(status);
        return analysisRepository.save(analysis).getId();
    }

    private AnalyzeRequestDTO requestFor(UUID id) {
        return new AnalyzeRequestDTO(id, resume.getS3Key(), new KnownPiiDTO("Test User", user.getEmail(), null));
    }

    private ResultActions pollLatest() throws Exception {
        return mockMvc.perform(get("/resumes/analysis").header("Authorization", bearerToken))
                .andExpect(status().isOk());
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okResult(String correlationId) {
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
