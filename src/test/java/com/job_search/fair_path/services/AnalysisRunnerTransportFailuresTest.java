package com.job_search.fair_path.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.config.AiServiceProperties;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;
import com.job_search.fair_path.dataTransferObject.analysis.KnownPiiDTO;
import com.job_search.fair_path.repository.AnalysisRepository;

/**
 * The outcomes that need a dead socket, which the WireMock stub can't give:
 * a refused connection (one auto-retry) and a read timeout (no auto-retry).
 */
class AnalysisRunnerTransportFailuresTest {

    private final UUID id = UUID.randomUUID();
    private final AnalyzeRequestDTO request = new AnalyzeRequestDTO(id, "resumes/x.pdf",
            new KnownPiiDTO(null, null, null));

    private AnalysisRepository repository;
    private ResumeAnalysisAiClient aiClient;
    private AnalysisRunner runner;

    @BeforeEach
    void setUp() {
        repository = mock(AnalysisRepository.class);
        aiClient = mock(ResumeAnalysisAiClient.class);
        when(repository.claim(any(UUID.class), any(Instant.class))).thenReturn(1);
        AiServiceProperties properties = new AiServiceProperties("http://unused", "token",
                Duration.ofSeconds(5), Duration.ofSeconds(120), Duration.ZERO);
        runner = new AnalysisRunner(repository, aiClient, new ObjectMapper(), properties);
    }

    @Test
    void aRefusedConnectionIsRetriedOnceThenFails() {
        when(aiClient.analyze(request))
                .thenThrow(new ResourceAccessException("I/O error", new ConnectException("refused")));

        runner.run(request);

        verify(aiClient, times(2)).analyze(request);
        verify(repository).failProcessing(id, "INTERNAL_ERROR");
    }

    @Test
    void aRefusedConnectionThatRecoversOnTheRetryIsNotFailed() {
        when(aiClient.analyze(request))
                .thenThrow(new ResourceAccessException("I/O error", new ConnectException("refused")))
                .thenReturn(null); // a null response is left for the sweep, which is not a failure

        runner.run(request);

        verify(aiClient, times(2)).analyze(request);
        verify(repository, never()).failProcessing(any(UUID.class), anyString());
    }

    @Test
    void aReadTimeoutFailsAsTimedOutWithoutAutoRetry() {
        when(aiClient.analyze(request))
                .thenThrow(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        runner.run(request);

        verify(aiClient, times(1)).analyze(request);
        verify(repository).failProcessing(id, "ANALYSIS_TIMED_OUT");
    }
}
