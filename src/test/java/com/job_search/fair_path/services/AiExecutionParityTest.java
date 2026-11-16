package com.job_search.fair_path.services;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.config.AiServiceProperties;
import com.job_search.fair_path.exceptions.AiServiceException;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;
import com.job_search.fair_path.dataTransferObject.analysis.KnownPiiDTO;
import com.job_search.fair_path.repository.AnalysisRepository;

/** Exercise BOTH real feature runners against the pre-refactor Analysis outcome contract. */
class AiExecutionParityTest {
    static Stream<Arguments> failures() {
        return Stream.of("analysis", "comparison").flatMap(feature -> Stream.of(
            Arguments.of(feature, "refused", 2, "INTERNAL_ERROR", true),
            Arguments.of(feature, "timeout", 1, "ANALYSIS_TIMED_OUT", true),
            Arguments.of(feature, "bare503", 2, "INTERNAL_ERROR", true),
            Arguments.of(feature, "wrapped503", 1, "INTERNAL_ERROR", true),
            Arguments.of(feature, "typed503", 1, "AI_UNAVAILABLE", true),
            Arguments.of(feature, "extraction", 1, "TEXT_NOT_EXTRACTABLE", false),
            Arguments.of(feature, "configuration", 1, "AI_CONFIGURATION_ERROR", true),
            Arguments.of(feature, "bare429", 1, "INTERNAL_ERROR", true),
            Arguments.of(feature, "bare500", 1, "INTERNAL_ERROR", true),
            Arguments.of(feature, "invalid", 1, "INTERNAL_ERROR", true)
        ));
    }

    @org.junit.jupiter.api.Test
    void interruptedRetryPreservesInterruptAndDoesNotSendSecondRequest() {
        var execution = new AiRequestExecution(new AiServiceProperties("http://unused", "token",
            Duration.ofSeconds(5), Duration.ofSeconds(120), Duration.ofSeconds(5), 8192));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var refusal = new ResourceAccessException("synthetic", new ConnectException());
        Thread.currentThread().interrupt();
        try {
            assertSame(refusal, assertThrows(ResourceAccessException.class, () -> execution.call(UUID.randomUUID(), () -> {
                calls.incrementAndGet();
                throw refusal;
            })));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, calls.get());
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest
    @MethodSource("failures")
    void bothRunnersPreserveOriginalAnalysisPolicy(String feature, String kind,
            int calls, String code, boolean retryable) {
        UUID id = UUID.randomUUID();
        RuntimeException failure = switch (kind) {
            case "refused" -> new ResourceAccessException("synthetic", new ConnectException());
            case "timeout" -> new ResourceAccessException("synthetic", new SocketTimeoutException());
            case "bare503" -> new AiServiceException(503, null, null, false);
            case "wrapped503" -> new org.springframework.web.client.RestClientException("synthetic wrapper", new AiServiceException(503, null, null, false));
            case "typed503" -> new AiServiceException(503, id.toString(), "AI_UNAVAILABLE", true, 60);
            case "extraction" -> new AiServiceException(422, id.toString(), "TEXT_NOT_EXTRACTABLE", false);
            case "configuration" -> new AiServiceException(502, id.toString(), "AI_CONFIGURATION_ERROR", false);
            case "bare429" -> new AiServiceException(429, null, null, false);
            case "bare500" -> new AiServiceException(500, null, null, false);
            default -> new IllegalArgumentException("synthetic invalid output");
        };
        var execution = new AiRequestExecution(new AiServiceProperties("http://unused", "token",
            Duration.ofSeconds(5), Duration.ofSeconds(120), Duration.ZERO, 8192));
        if (feature.equals("analysis")) {
            var repo = mock(AnalysisRepository.class);
            var client = mock(ResumeAnalysisAiClient.class);
            var request = new AnalyzeRequestDTO(id, "synthetic.pdf", new KnownPiiDTO(null, null, null));
            when(repo.claim(eq(id), any())).thenReturn(1);
            when(client.analyze(request)).thenThrow(failure);
            new AnalysisRunner(repo, client, new ObjectMapper(), execution, mock(AnalysisService.class)).run(request);
            verify(client, times(calls)).analyze(request);
            verify(repo).failProcessing(id, code);
            assertEquals(retryable, AnalysisErrorCodes.isRetryable(code));
        } else {
            var queue = mock(JobComparisonQueue.class);
            var client = mock(JobComparisonClient.class);
            var task = new JobComparisonQueue.Task(UUID.randomUUID(), id, UUID.randomUUID(),
                "job", "synthetic.pdf", "Java", 1, UUID.randomUUID(), Instant.now());
            when(queue.claim()).thenReturn(Optional.of(task));
            when(client.compare(task)).thenThrow(failure);
            new JobComparisonWorker(queue, client, execution).processNext();
            verify(client, times(calls)).compare(task);
            verify(queue).fail(eq(task), argThat(error -> code.equals(error.errorCode()) && error.retryable() == retryable));
        }
    }
}
