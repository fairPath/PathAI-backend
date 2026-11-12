package com.job_search.fair_path.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.job_search.fair_path.dataTransferObject.JobResultDTO;
import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.entity.SavedJobsEntity;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.ResumeRepository;
import com.job_search.fair_path.repository.SavedJobsRepository;
import com.job_search.fair_path.repository.UserRepository;

import software.amazon.awssdk.services.s3.S3Client;

class SavedJobQueueTests {
    private final SavedJobsRepository jobs = mock(SavedJobsRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final S3Client s3 = mock(S3Client.class);
    private final SavedJobService service = new SavedJobService(jobs, users, resumes, s3, "bucket");
    private final User user = new User();
    private final Resume resume = new Resume();
    private final SavedJobsEntity saved = new SavedJobsEntity();
    private final JobResultDTO request = new JobResultDTO();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        user.setId(UUID.randomUUID());
        resume.setId(UUID.randomUUID());
        resume.setUserId(user.getId());
        resume.setUpdatedAt(Instant.now());
        resume.setStatus(Resume.Status.ACTIVE);
        saved.setId(UUID.randomUUID());
        saved.setUserId(user.getId());
        saved.setJobId("job");
        saved.setJobDescription("description");
        saved.setResumeId(resume.getId());
        saved.setComparisonStatus("PENDING");
        saved.setComparisonRequestId(requestId);
        saved.setComparisonAttempts(1);
        saved.setResumeSnapshot(Map.of("sourceUpdatedAt", resume.getUpdatedAt().toString(),
                "s3Key", "resumes/" + user.getId() + "/saved-job-snapshots/snapshot.pdf"));
        request.setJobId("job");
        request.setJobDescription("description");
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(jobs.findForUpdate(user.getId(), "job")).thenReturn(Optional.of(saved));
        when(resumes.findByUserId(user.getId())).thenReturn(Optional.of(resume));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(user, null, List.of());
    }

    @Test
    void fullDescriptionIsStoredInOneFieldAndReturnedWithoutTruncation() {
        when(jobs.findForUpdate(user.getId(), "job")).thenReturn(Optional.empty());
        when(resumes.findByUserId(user.getId())).thenReturn(Optional.empty());
        String first = "A".repeat(254) + "\uD83D\uDE00";
        String second = "B".repeat(252);
        String remainder = "C".repeat(1000) + " final requirement";
        String full = first + second + remainder;
        request.setJobDescription(full);

        var result = service.prepareJob(request, auth());

        var argument = org.mockito.ArgumentCaptor.forClass(SavedJobsEntity.class);
        verify(jobs).saveAndFlush(argument.capture());
        assertEquals(full, argument.getValue().getJobDescription());
        assertEquals(full, result.getJobDescription());
    }

    @Test
    void shortDescriptionDoesNotDuplicateItsBeginning() {
        when(jobs.findForUpdate(user.getId(), "job")).thenReturn(Optional.empty());
        when(resumes.findByUserId(user.getId())).thenReturn(Optional.empty());
        request.setJobDescription("A short description");

        var result = service.prepareJob(request, auth());

        var argument = org.mockito.ArgumentCaptor.forClass(SavedJobsEntity.class);
        verify(jobs).saveAndFlush(argument.capture());
        assertEquals("A short description", argument.getValue().getJobDescription());
        assertEquals("A short description", result.getJobDescription());
    }

    @Test
    void savedJobDetailPreservesFullTextAndWhitespace() {
        saved.setJobDescription("First paragraph\n\nSecond paragraph with the final requirement.");
        when(jobs.findByUserIdAndJobId(user.getId(), "job")).thenReturn(Optional.of(saved));
        assertEquals("First paragraph\n\nSecond paragraph with the final requirement.",
                service.getSavedJobForUser(auth(), "job").getJobDescription());
    }

    @Test
    void newSaveCommitsPendingWorkAndDoesNotExposePrivateSnapshotKey() {
        when(jobs.findForUpdate(user.getId(), "job")).thenReturn(Optional.empty());
        resume.setS3Bucket("source-bucket");
        resume.setS3Key("original.pdf");
        resume.setFileName("resume.pdf");
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.prepareJob(request, auth());
            assertEquals("PENDING", result.getComparisonStatus());
            assertNotNull(result.getComparisonRequestId());
            assertFalse(result.getResumeSnapshot().containsKey("s3Key"));
            var argument = org.mockito.ArgumentCaptor.forClass(SavedJobsEntity.class);
            verify(jobs).saveAndFlush(argument.capture());
            assertEquals(0, argument.getValue().getComparisonAttempts());
            assertNotNull(argument.getValue().getComparisonNextAttemptAt());
            assertTrue(argument.getValue().getResumeSnapshot().containsKey("s3Key"));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void repeatedPendingSaveDoesNotRestartWorkOrCopyResume() {
        var result = service.prepareJob(request, auth());
        assertEquals(requestId, result.getComparisonRequestId());
        assertEquals(1, saved.getComparisonAttempts());
        assertEquals("PENDING", result.getComparisonStatus());
        verifyNoInteractions(s3);
    }

    @Test
    void manualRetryCreatesNewGenerationAndClearsOldLease() {
        saved.setComparisonStatus("FAILED");
        saved.setComparisonAttempts(3);
        saved.setComparisonLeaseToken(UUID.randomUUID());
        saved.setComparisonLeaseUntil(Instant.now().plusSeconds(180));
        var result = service.prepareJob(request, auth());
        assertNotEquals(requestId, result.getComparisonRequestId());
        assertEquals("PENDING", result.getComparisonStatus());
        assertEquals(0, saved.getComparisonAttempts());
        assertNull(saved.getComparisonLeaseToken());
        assertNull(saved.getComparisonLeaseUntil());
        assertNotNull(saved.getComparisonNextAttemptAt());
        verifyNoInteractions(s3);
    }

    @Test
    void missingResumeSavesWithoutQueueingAiWork() {
        when(jobs.findForUpdate(user.getId(), "job")).thenReturn(Optional.empty());
        when(resumes.findByUserId(user.getId())).thenReturn(Optional.empty());
        var result = service.prepareJob(request, auth());
        assertEquals("NOT_REQUESTED", result.getComparisonStatus());
        assertNull(result.getComparisonRequestId());
        verify(jobs).saveAndFlush(any());
        verifyNoInteractions(s3);
    }

    @Test
    void pollingUsesAuthenticatedOwnerAndRejectsUnboundedInput() {
        when(jobs.findByUserIdAndJobIdIn(user.getId(), List.of("job"))).thenReturn(List.of(saved));
        assertEquals(requestId, service.getComparisonStatuses(auth(), List.of("job")).get(0).getComparisonRequestId());
        verify(jobs).findByUserIdAndJobIdIn(user.getId(), List.of("job"));
        assertThrows(IllegalArgumentException.class, () -> service.getComparisonStatuses(auth(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.getComparisonStatuses(auth(), java.util.Collections.nCopies(151, "job")));
    }
}
