package com.job_search.fair_path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.job_search.fair_path.dataTransferObject.analysis.ScoresDTO;
import com.job_search.fair_path.entity.Analysis;
import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.repository.AnalysisRepository;
import com.job_search.fair_path.services.AnalysisCompletion;
import com.job_search.fair_path.support.AnalysisTestSupport;
import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Ticket #60: one Analysis at a time, the Cooldown, and Resume replace and
 * delete that keep the Usage record. Real Postgres (with the hand-written
 * index from reset.sql), real security filter, the AI service stubbed.
 */
class UsageLimitsTest extends AnalysisTestSupport {

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisCompletion completion;

    @Autowired
    private JdbcTemplate jdbc;

    // ---- the Usage record ----

    @Test
    void usageRecordHasExactlyTheFourColumnsAndNothingFromAResume() {
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_name = 'usage_record' order by column_name
                """, String.class);

        assertThat(columns).containsExactly("id", "kind", "occurred_at", "user_id");
    }

    @Test
    void aCompletedAnalysisWritesExactlyOneUsageRecordForTheUser() throws Exception {
        stubAiService(0);

        analyze().andExpect(status().isAccepted());
        awaitStatus("COMPLETED");

        assertThat(usageCount("ANALYSIS_COMPLETED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select user_id from usage_record", UUID.class)).isEqualTo(user.getId());
    }

    @Test
    void aLateResultWritesNoUsageRecord() {
        UUID id = insertAnalysis(Analysis.Status.FAILED); // e.g. the sweep failed it first

        int rows = completion.complete(id, new ScoresDTO(0, 100, 100, 67), "[]", null, "0.1.0");

        assertThat(rows).isZero();
        assertThat(usageCount(null)).isZero();
    }

    @Test
    @Sql(statements = "ALTER TABLE usage_record ADD CONSTRAINT zz_block_inserts CHECK (false)")
    @Sql(statements = "ALTER TABLE usage_record DROP CONSTRAINT IF EXISTS zz_block_inserts",
            executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void ifTheUsageInsertFailsTheCompletionRollsBackWithIt() throws Exception {
        stubAiService(0);

        analyze().andExpect(status().isAccepted());

        // The runner fails the row instead of leaving it COMPLETED with no Usage record.
        awaitStatus("FAILED");
        assertThat(usageCount(null)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis where status = 'COMPLETED'", Integer.class))
                .isZero();
    }

    // ---- one at a time ----

    @Test
    void theIndexRejectsASecondInFlightAnalysisForTheSameUser() {
        insertAnalysis(Analysis.Status.PENDING);

        assertThatThrownBy(() -> insertAnalysis(Analysis.Status.PROCESSING))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(constraintName(e)).isEqualToIgnoringCase(Analysis.ONE_IN_FLIGHT_INDEX));
    }

    @Test
    void theIndexAllowsFinishedAnalysesNextToOneInFlight() {
        insertAnalysis(Analysis.Status.COMPLETED);
        insertAnalysis(Analysis.Status.FAILED);
        insertAnalysis(Analysis.Status.PENDING);

        assertThat(analysisRepository.count()).isEqualTo(3);
    }

    @Test
    void analyzeWhileOneIsInFlightIsRejectedWithoutCallingTheAiService() throws Exception {
        insertAnalysis(Analysis.Status.PROCESSING);

        analyze().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error_code").value("ANALYSIS_IN_PROGRESS"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retry_at").value(nullValue()));

        aiService.verify(0, anyRequestedFor(anyUrl()));
        assertThat(analysisRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentAnalyzeRequestsLetExactlyOneThrough() throws Exception {
        // Held long enough that every request in the burst sees the first still in flight.
        stubAiService(3_000);
        int clicks = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        List<Future<org.springframework.test.web.servlet.MvcResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < clicks; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return mockMvc.perform(MockMvcRequestBuilders.post("/resumes/analyze")
                            .header("Authorization", bearerToken)).andReturn();
                }));
            }
            go.countDown();

            int accepted = 0;
            for (Future<org.springframework.test.web.servlet.MvcResult> result : results) {
                var response = result.get().getResponse();
                if (response.getStatus() == 202) {
                    accepted++;
                } else {
                    assertThat(response.getStatus()).isEqualTo(429);
                    assertThat((String) JsonPath.read(response.getContentAsString(), "$.error_code"))
                            .isEqualTo("ANALYSIS_IN_PROGRESS");
                }
            }
            assertThat(accepted).isEqualTo(1);
            assertThat(analysisRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        awaitStatus("COMPLETED"); // do not leak a running Analysis into the next test
    }

    // ---- the Cooldown ----

    @Test
    void theCooldownRunsFromTheLastCompletionForTwentyFourHours() throws Exception {
        stubAiService(0);
        analyze().andExpect(status().isAccepted());
        awaitStatus("COMPLETED");
        Instant completedAt = jdbc.queryForObject("select occurred_at from usage_record", Instant.class);

        String body = analyze().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error_code").value("COOLDOWN_ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        Instant retryAt = Instant.parse(JsonPath.read(body, "$.retry_at"));
        assertThat(retryAt).isCloseTo(completedAt.plus(Duration.ofHours(24)), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));

        clock.advance(Duration.ofHours(23).plusMinutes(59));
        analyze().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error_code").value("COOLDOWN_ACTIVE"));

        clock.advance(Duration.ofMinutes(2));
        analyze().andExpect(status().isAccepted());
        awaitLatest("COMPLETED");
    }

    @Test
    void aFailedAnalysisDoesNotStartTheCooldown() throws Exception {
        insertAnalysis(Analysis.Status.FAILED);
        stubAiService(0);

        analyze().andExpect(status().isAccepted());
        awaitStatus("COMPLETED");
    }

    @Test
    void inProgressIsCheckedBeforeTheCooldown() throws Exception {
        jdbc.update("insert into usage_record (id, user_id, kind, occurred_at) values (?, ?, 'ANALYSIS_COMPLETED', now())",
                UUID.randomUUID(), user.getId());
        insertAnalysis(Analysis.Status.PENDING);

        analyze().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error_code").value("ANALYSIS_IN_PROGRESS"));
    }

    // ---- Resume replace and delete ----

    @Test
    void replacingWhileAnAnalysisIsInFlightIsRejected() throws Exception {
        insertAnalysis(Analysis.Status.PENDING);

        mockMvc.perform(MockMvcRequestBuilders.post("/resumes/presign-url")
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filename\":\"new.pdf\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error_code").value("ANALYSIS_IN_PROGRESS"));

        assertThat(resumeRepository.findByUserId(user.getId()).orElseThrow().getStatus())
                .isEqualTo(Resume.Status.ACTIVE);
    }

    @Test
    void deletingWhileAnAnalysisIsInFlightIsRejectedAndNothingIsDeleted() throws Exception {
        insertAnalysis(Analysis.Status.PROCESSING);

        mockMvc.perform(delete("/resumes/delete").header("Authorization", bearerToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error_code").value("ANALYSIS_IN_PROGRESS"));

        verify(s3Client, never()).deleteObject(anyDeleteObjectConsumer());
        assertThat(resumeRepository.findByUserId(user.getId())).isPresent();
        assertThat(analysisRepository.count()).isEqualTo(1);
    }

    @Test
    void confirmingAReplacementDeletesTheOldAnalysesButKeepsTheUsageRecord() throws Exception {
        insertAnalysis(Analysis.Status.COMPLETED);
        insertUsageRecord();
        markResumeAsReplacementAwaitingConfirm();

        confirm().andExpect(status().isOk());

        assertThat(analysisRepository.count()).isZero();
        assertThat(usageCount("ANALYSIS_COMPLETED")).isEqualTo(1);
        assertThat(resumeRepository.findByUserId(user.getId()).orElseThrow().getStatus())
                .isEqualTo(Resume.Status.ACTIVE);
    }

    @Test
    void aDuplicateConfirmDoesNotDeleteResultsMadeSinceTheFirst() throws Exception {
        markResumeAsReplacementAwaitingConfirm();
        confirm().andExpect(status().isOk());
        insertAnalysis(Analysis.Status.COMPLETED); // the user analyzed the new file

        confirm().andExpect(status().isOk()); // a double click or a retried request

        assertThat(analysisRepository.count()).isEqualTo(1);
    }

    @Test
    void deletingTheResumeDeletesItsAnalysesButKeepsTheUsageRecord() throws Exception {
        insertAnalysis(Analysis.Status.COMPLETED);
        insertUsageRecord();

        mockMvc.perform(delete("/resumes/delete").header("Authorization", bearerToken))
                .andExpect(status().isOk());

        assertThat(analysisRepository.count()).isZero();
        assertThat(usageCount("ANALYSIS_COMPLETED")).isEqualTo(1);
    }

    // ---- helpers ----

    private org.springframework.test.web.servlet.ResultActions analyze() throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/resumes/analyze").header("Authorization", bearerToken));
    }

    private org.springframework.test.web.servlet.ResultActions confirm() throws Exception {
        when(s3Client.headObject(anyHeadObjectConsumer()))
                .thenReturn(HeadObjectResponse.builder().contentLength(1234L).build());
        return mockMvc.perform(MockMvcRequestBuilders.post("/resumes/confirm")
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeId\":\"" + resume.getId() + "\"}"));
    }

    /** What presign leaves behind: the same Resume row, back to PENDING. */
    private void markResumeAsReplacementAwaitingConfirm() {
        jdbc.update("update resume set status = 'PENDING' where id = ?", resume.getId());
    }

    private void stubAiService(int delayMs) {
        aiService.stubFor(post(urlEqualTo("/resume"))
                .willReturn(aResponse()
                        .withFixedDelay(delayMs)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "correlation_id": "{{jsonPath request.body '$.correlation_id'}}",
                                  "scores": { "formatting": 0, "parseability": 100, "writing": 100, "overall": 67 },
                                  "issues": [ %s ],
                                  "model": null,
                                  "pipeline_version": "0.1.0"
                                }
                                """.formatted(MISSING_CONTACT_ISSUE_JSON))
                        .withTransformers("response-template")));
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                jdbc.queryForObject("select count(*) from analysis where status = ?", Integer.class, expected))
                .isEqualTo(1));
    }

    private void awaitLatest(String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select status from analysis order by created_at desc limit 1", String.class)).isEqualTo(expected));
    }

    private int usageCount(String kind) {
        return kind == null
                ? jdbc.queryForObject("select count(*) from usage_record", Integer.class)
                : jdbc.queryForObject("select count(*) from usage_record where kind = ?", Integer.class, kind);
    }

    private void insertUsageRecord() {
        jdbc.update("insert into usage_record (id, user_id, kind, occurred_at) values (?, ?, 'ANALYSIS_COMPLETED', now())",
                UUID.randomUUID(), user.getId());
    }

    private UUID insertAnalysis(Analysis.Status status) {
        Analysis analysis = new Analysis();
        analysis.setResume(resume);
        analysis.setUserId(user.getId());
        analysis.setStatus(status);
        return analysisRepository.saveAndFlush(analysis).getId();
    }

    private static String constraintName(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    // Disambiguate the (Consumer) overloads from the (Request) overloads.
    private static Consumer<HeadObjectRequest.Builder> anyHeadObjectConsumer() {
        return any();
    }

    private static Consumer<DeleteObjectRequest.Builder> anyDeleteObjectConsumer() {
        return any();
    }
}
