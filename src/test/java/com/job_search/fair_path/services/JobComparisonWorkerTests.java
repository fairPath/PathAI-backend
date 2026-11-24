package com.job_search.fair_path.services;

import com.job_search.fair_path.exceptions.ComparisonFailure;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobComparisonWorkerTests {
    private AiRequestExecution execution() {
        return new AiRequestExecution(new com.job_search.fair_path.config.AiServiceProperties(
            "http://unused", "token", java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(120), java.time.Duration.ZERO, 8192));
    }
    private final JobComparisonQueue queue = mock(JobComparisonQueue.class);
    private final JobComparisonClient client = mock(JobComparisonClient.class);
    private final JobComparisonQueue.Task task = new JobComparisonQueue.Task(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "job", "private-key", "description", 1, UUID.randomUUID(), java.time.Instant.now());

    @Test
    void successfulComparisonIsPersistedWithItsClaim() throws Exception {
        when(queue.claim()).thenReturn(Optional.of(task));
        var result = new JobComparisonClient.Result(85, List.of("Java"), List.of(), "Experience", List.of(), 90, 70, "Highlight your relevant projects.");
        when(client.compare(task)).thenReturn(result);
        when(queue.complete(task, result)).thenReturn(true);

        new JobComparisonWorker(queue, client, execution()).processNext();

        verify(queue).complete(task, result);
        verify(queue, never()).fail(any(), any());
    }

    @Test
    void failedInferenceIsRetriedWithoutDeletingTheSavedJob() throws Exception {
        when(queue.claim()).thenReturn(Optional.of(task));
        when(client.compare(task)).thenThrow(new org.springframework.web.client.ResourceAccessException("Synthetic refusal", new java.net.ConnectException()));

        new JobComparisonWorker(queue, client, execution()).processNext();

        verify(queue).fail(eq(task), argThat(f -> f.retryable() && "INTERNAL_ERROR".equals(f.errorCode())));
        verify(client, times(2)).compare(task);
        verify(queue, never()).complete(any(), any());
    }

    @Test
    void staleResumeResultIsDiscardedAndAllowsManualRetry() throws Exception {
        when(queue.claim()).thenReturn(Optional.of(task));
        var result = new JobComparisonClient.Result(85, List.of(), List.of(), "", List.of(), 90, 70, "Highlight your relevant projects.");
        when(client.compare(task)).thenReturn(result);
        when(queue.complete(task, result)).thenReturn(false);
        new JobComparisonWorker(queue, client, execution()).processNext();
        verify(queue).fail(eq(task), argThat(f -> f.retryable() && f.errorCode().equals("RESUME_CHANGED")));
    }

    @Test
    void featurePreconditionRetainsItsExplicitFailure() throws Exception {
        when(queue.claim()).thenReturn(Optional.of(task));
        when(client.compare(task)).thenThrow(new ComparisonFailure("RESUME_REQUIRED", false));
        new JobComparisonWorker(queue, client, execution()).processNext();
        verify(queue).fail(eq(task), argThat(f -> !f.retryable() && f.errorCode().equals("RESUME_REQUIRED")));
    }

    @Test
    void databaseOutageDoesNotStopFutureWorkerPolls() {
        when(queue.claim()).thenThrow(new IllegalStateException("Database unavailable"))
                .thenReturn(Optional.empty());
        var worker = new JobComparisonWorker(queue, client, execution());

        assertDoesNotThrow(worker::processNext);
        assertDoesNotThrow(worker::processNext);
        verify(queue, times(2)).claim();
    }

    @Test
    void malformedModelResultCannotBeStored() {
        assertThrows(IllegalArgumentException.class,
                () -> new JobComparisonClient.Result(101, List.of(), List.of(), "", List.of(), 90, 70, "Highlight your relevant projects.").validate());
        assertThrows(IllegalArgumentException.class,
                () -> new JobComparisonClient.Result(50, null, List.of(), "", List.of(), 90, 70, "Highlight your relevant projects.").validate());
    }

    @Test
    void transientDatabaseFailureRemainsRetryable() throws Exception {
        when(queue.claim()).thenReturn(Optional.of(task));
        var result = new JobComparisonClient.Result(85, List.of(), List.of(), "", List.of(), 90, 80, "Highlight relevant projects.");
        when(client.compare(task)).thenReturn(result);
        when(queue.complete(task, result)).thenThrow(new org.springframework.dao.CannotAcquireLockException("Synthetic lock timeout"));
        new JobComparisonWorker(queue, client, execution()).processNext();
        verify(queue).fail(eq(task), argThat(failure -> failure.retryable()));
    }

}
