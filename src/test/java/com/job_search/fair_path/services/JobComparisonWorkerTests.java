package com.job_search.fair_path.services;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobComparisonWorkerTests {
    private final JobComparisonQueue queue = mock(JobComparisonQueue.class);
    private final JobComparisonClient client = mock(JobComparisonClient.class);
    private final JobComparisonQueue.Task task = new JobComparisonQueue.Task(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "job", "private-key", "description", 1);

    @Test
    void successfulComparisonIsPersistedWithItsClaim() throws Exception {
        when(queue.claim()).thenReturn(Optional.of(task));
        var result = new JobComparisonClient.Result(85, List.of("Java"), List.of(), "Experience", List.of());
        when(client.compare(task)).thenReturn(result);
        when(queue.complete(task, result)).thenReturn(true);

        new JobComparisonWorker(queue, client).processNext();

        verify(queue).complete(task, result);
        verify(queue, never()).retryOrFail(any());
    }

    @Test
    void failedInferenceIsRetriedWithoutDeletingTheSavedJob() throws Exception {
        when(queue.claim()).thenReturn(Optional.of(task));
        when(client.compare(task)).thenThrow(new IllegalStateException("Provider unavailable"));

        new JobComparisonWorker(queue, client).processNext();

        verify(queue).retryOrFail(task);
        verify(queue, never()).complete(any(), any());
    }

    @Test
    void databaseOutageDoesNotStopFutureWorkerPolls() {
        when(queue.claim()).thenThrow(new IllegalStateException("Database unavailable"))
                .thenReturn(Optional.empty());
        var worker = new JobComparisonWorker(queue, client);

        assertDoesNotThrow(worker::processNext);
        assertDoesNotThrow(worker::processNext);
        verify(queue, times(2)).claim();
    }

    @Test
    void malformedModelResultCannotBeStored() {
        assertThrows(IllegalArgumentException.class,
                () -> new JobComparisonClient.Result(101, List.of(), List.of(), "", List.of()).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new JobComparisonClient.Result(50, null, List.of(), "", List.of()).validate());
    }
}
