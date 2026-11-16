package com.job_search.fair_path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import com.jayway.jsonpath.JsonPath;
import com.job_search.fair_path.services.JobComparisonWorker;
import com.job_search.fair_path.support.AnalysisTestSupport;

class JobComparisonEndpointsTest extends AnalysisTestSupport {
    @Autowired private JobComparisonWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private com.job_search.fair_path.services.SavedJobService savedJobService;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("comparisonExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor comparisonExecutor;

    private void save() throws Exception {
        mockMvc.perform(post("/saved-jobs/prepare").header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jobId":"job","title":"Engineer","jobDescription":"Java and Go"}
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparisonStatus").value("NOT_REQUESTED"));
    }

    private String compare() throws Exception {
        return mockMvc.perform(post("/saved-jobs/compare").header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"job\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.comparisonStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void saveDoesNotQueueAndExplicitClickUsesTheCurrentResume() throws Exception {
        save();
        worker.processNext();
        aiService.verify(0, anyRequestedFor(anyUrl()));
        verifyNoInteractions(s3Client);
        String id = JsonPath.read(compare(), "$.comparisonRequestId");
        // Duplicate clicks reuse the pending generation.
        assertEquals(id, JsonPath.read(compare(), "$.comparisonRequestId"));
        // An upload completed after clicking: processing must use the new live key/version.
        resume.setS3Key("resumes/latest.pdf");
        resumeRepository.saveAndFlush(resume);
        aiService.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/job-comparison"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                    {"correlation_id":"%s","match_score":85,"matched_skills":["Java"],
                     "missing_skills":["Go"],"relevant_experience":"Backend","other_gaps":[],
                     "skill_match_score":90,"experience_match_score":80,"resume_advice":"Highlight your relevant projects."}
                    """.formatted(id))));
        worker.processNext();
        mockMvc.perform(get("/saved-jobs/status").param("jobId", "job").header("Authorization", bearerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].comparisonStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[0].matchScore").value(85))
                .andExpect(jsonPath("$[0].skillMatchScore").value(90))
                .andExpect(jsonPath("$[0].experienceMatchScore").value(80))
                .andExpect(jsonPath("$[0].resumeAdvice").value("Highlight your relevant projects."))
                .andExpect(jsonPath("$[0].comparisonCompletedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].resumeSnapshot").doesNotExist());
        aiService.verify(postRequestedFor(urlEqualTo("/job-comparison"))
                .withRequestBody(matchingJsonPath("$.s3_key", equalTo("resumes/latest.pdf")))
                .withRequestBody(matchingJsonPath("$.correlation_id", equalTo(id))));
        mockMvc.perform(get("/saved-jobs/detail").param("jobId", "job").header("Authorization", bearerToken))
                .andExpect(jsonPath("$.skillMatchScore").value(90))
                .andExpect(jsonPath("$.experienceMatchScore").value(80))
                .andExpect(jsonPath("$.resumeAdvice").value("Highlight your relevant projects."));
        String recheck = compare();
        assertNull(JsonPath.read(recheck, "$.matchScore"));
        assertNull(JsonPath.read(recheck, "$.skillMatchScore"));
        assertNull(JsonPath.read(recheck, "$.experienceMatchScore"));
        assertNull(JsonPath.read(recheck, "$.resumeAdvice"));
    }

    @Test
    void internalAuthenticationFailureIsStoredAndNeverRedirectsTheUser() throws Exception {
        save();
        compare();
        aiService.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/job-comparison"))
                .willReturn(aResponse().withStatus(401)));
        worker.processNext();
        worker.processNext();
        mockMvc.perform(get("/saved-jobs/status").param("jobId", "job").header("Authorization", bearerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].comparisonStatus").value("FAILED"))
                .andExpect(jsonPath("$[0].comparisonErrorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$[0].comparisonRetryable").value(true));
        aiService.verify(1, postRequestedFor(urlEqualTo("/job-comparison")));
        // A fresh, deliberate request (e.g. after refreshing) gets a new generation.
        assertNotNull(JsonPath.read(compare(), "$.comparisonRequestId"));
    }

    @Test
    void transientServiceFailureRetriesThenAllowsManualRetry() throws Exception {
        save();
        compare();
        aiService.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/job-comparison"))
                .willReturn(aResponse().withStatus(503)));
        worker.processNext();
        worker.processNext();
        worker.processNext();
        mockMvc.perform(get("/saved-jobs/status").param("jobId", "job").header("Authorization", bearerToken))
                .andExpect(jsonPath("$[0].comparisonStatus").value("FAILED"))
                .andExpect(jsonPath("$[0].comparisonRetryable").value(true));
        aiService.verify(2, postRequestedFor(urlEqualTo("/job-comparison")));
    }

    @Test
    void comparisonDispatchesAfterCommitAndRollbackNeverCallsAi() throws Exception {
        save();
        doCallRealMethod().when(comparisonWorker).onComparisonRequested(any());
        aiService.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/job-comparison"))
                .willReturn(aResponse().withStatus(503)));
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                transactionManager);
        transactions.executeWithoutResult(transaction -> {
            savedJobService.requestComparison("job",
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null));
            aiService.verify(0, postRequestedFor(urlEqualTo("/job-comparison")));
            transaction.setRollbackOnly();
        });
        assertEquals("NOT_REQUESTED", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs WHERE job_id = 'job'", String.class));
        aiService.verify(0, postRequestedFor(urlEqualTo("/job-comparison")));
        compare();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertEquals("FAILED", jdbc.queryForObject("SELECT comparison_status FROM saved_jobs WHERE job_id = 'job'", String.class)));
        aiService.verify(2, postRequestedFor(urlEqualTo("/job-comparison")));
        assertEquals(8, comparisonExecutor.getCorePoolSize());
    }

    @Test
    void comparisonOnlyLooksUpTheAuthenticatedUsersSavedJob() throws Exception {
        save();
        var other = new com.job_search.fair_path.entity.User(
                "Other", "User", UUID.randomUUID().toString(), UUID.randomUUID()+"@example.invalid", "fake");
        other.setEnabled(true);
        other = userRepository.saveAndFlush(other);
        mockMvc.perform(post("/saved-jobs/compare")
                .header("Authorization", "Bearer " + jwtService.generateToken(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"job\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error_code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void expiredOrMissingUserSessionRequiresLogin() throws Exception {
        mockMvc.perform(post("/saved-jobs/compare")
                .contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"job\"}"))
                .andExpect(status().isUnauthorized());
    }
}
