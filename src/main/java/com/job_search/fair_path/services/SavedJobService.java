package com.job_search.fair_path.services;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.job_search.fair_path.dataTransferObject.JobResultDTO;
import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.entity.SavedJobsEntity;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.ResumeRepository;
import com.job_search.fair_path.repository.SavedJobsRepository;
import com.job_search.fair_path.repository.UserRepository;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@Service
public class SavedJobService {
    private static final Logger logger = LoggerFactory.getLogger(SavedJobService.class);
    private static final int MAX_SAVED_JOBS = 150;

    private final SavedJobsRepository savedJobsRepository;
    private final UserRepository userRepository;
    private final ResumeRepository resumeRepository;
    private final S3Client s3Client;
    private final String resumeBucket;

    public SavedJobService(
            SavedJobsRepository savedJobsRepository,
            UserRepository userRepository,
            ResumeRepository resumeRepository,
            S3Client s3Client,
            @org.springframework.beans.factory.annotation.Value("${aws.s3.bucket}") String resumeBucket) {
        this.savedJobsRepository = savedJobsRepository;
        this.userRepository = userRepository;
        this.resumeRepository = resumeRepository;
        this.s3Client = s3Client;
        this.resumeBucket = resumeBucket;
    }

    public List<JobResultDTO> getSavedJobsForUser(Authentication authentication) {
        UUID userId = authenticatedUser(authentication).getId();
        return savedJobsRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(SavedJobsEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public JobResultDTO getSavedJobForUser(Authentication authentication, String jobId) {
        UUID userId = authenticatedUser(authentication).getId();
        return savedJobsRepository.findByUserIdAndJobId(userId, jobId)
                .map(this::toDto)
                .orElse(null);
    }

    @Transactional
    public JobResultDTO prepareJob(JobResultDTO request, Authentication authentication) {
        if (request.getJobId() == null || request.getJobId().isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }

        User user = authenticatedUser(authentication);
        UUID userId = user.getId();
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));

        SavedJobsEntity savedJob = savedJobsRepository.findForUpdate(userId, request.getJobId())
                .orElse(null);
        if (savedJob == null) {
            if (savedJobsRepository.countByUserId(userId) >= MAX_SAVED_JOBS) {
                throw new SavedJobLimitExceededException(MAX_SAVED_JOBS);
            }
            savedJob = new SavedJobsEntity();
            savedJob.setUserId(userId);
            savedJob.setJobId(request.getJobId());
        }

        String previousDescription = getDescription(savedJob);
        String previousSnapshotKey = snapshotKey(savedJob.getResumeSnapshot());
        String description = request.getJobDescription() == null ? "" : request.getJobDescription();

        savedJob.setCompanyName(request.getCompanyName());
        savedJob.setJobTitle(request.getTitle());
        savedJob.setRedirectUrl(request.getRedirectUrl());
        savedJob.setDateCreated(request.getDateCreated());
        savedJob.setLocation(request.getLocation());
        savedJob.setSalaryMin(request.getSalaryMin());
        savedJob.setSalaryMax(request.getSalaryMax());
        savedJob.setJobDescription(description);

        Resume activeResume = resumeRepository.findByUserId(userId)
                .filter(resume -> resume.getStatus() == Resume.Status.ACTIVE)
                .orElse(null);
        if (activeResume == null) {
            if (!Objects.equals(previousDescription, description) || savedJob.getComparisonStatus() == null) {
                clearComparison(savedJob);
                savedJob.setComparisonStatus("NOT_REQUESTED");
                savedJob.setComparisonRequestId(null);
                savedJob.setComparisonLeaseToken(null);
                savedJob.setComparisonLeaseUntil(null);
            }
            return toDto(savedJobsRepository.saveAndFlush(savedJob));
        }

        boolean sameDescription = Objects.equals(previousDescription, description);
        boolean sameResumeSnapshot = hasSnapshotFor(savedJob, activeResume);
        if (sameDescription && sameResumeSnapshot
                && ("COMPLETED".equals(savedJob.getComparisonStatus())
                    || ("PENDING".equals(savedJob.getComparisonStatus()) && savedJob.getComparisonRequestId() != null))) {
            return toDto(savedJobsRepository.saveAndFlush(savedJob));
        }

        String activeSnapshotKey = snapshotKey(savedJob.getResumeSnapshot());
        boolean canReuseSnapshot = sameResumeSnapshot && activeSnapshotKey != null;
        String immutableS3Key = canReuseSnapshot ? activeSnapshotKey : createResumeSnapshot(activeResume, userId);
        if (!canReuseSnapshot) {
            registerDeleteOnRollback(resumeBucket, immutableS3Key);
            savedJob.setResumeSnapshot(snapshotMetadata(activeResume, immutableS3Key));
        }

        savedJob.setResumeId(activeResume.getId());
        clearComparison(savedJob);
        savedJob.setComparisonStatus("PENDING");
        savedJob.setComparisonRequestId(UUID.randomUUID());
        savedJob.setComparisonAttempts(0);
        savedJob.setComparisonLeaseToken(null);
        savedJob.setComparisonLeaseUntil(null);
        savedJob.setComparisonNextAttemptAt(Instant.now());
        savedJob = savedJobsRepository.saveAndFlush(savedJob);
        if (previousSnapshotKey != null && !previousSnapshotKey.equals(immutableS3Key)) {
            registerDeleteAfterCommit(resumeBucket, previousSnapshotKey);
        }
        return toDto(savedJob);
    }

    public List<JobResultDTO> getComparisonStatuses(Authentication authentication, List<String> jobIds) {
        if (jobIds.isEmpty() || jobIds.size() > MAX_SAVED_JOBS
                || jobIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 200)) {
            throw new IllegalArgumentException("Provide between 1 and 150 valid job IDs");
        }
        UUID userId = authenticatedUser(authentication).getId();
        return savedJobsRepository.findByUserIdAndJobIdIn(userId, jobIds).stream().map(this::toDto).toList();
    }

    @Transactional
    public void deleteSavedJob(Authentication authentication, String jobId) {
        UUID userId = authenticatedUser(authentication).getId();
        savedJobsRepository.findForUpdate(userId, jobId).ifPresent(savedJob -> {
            String objectKey = snapshotKey(savedJob.getResumeSnapshot());
            savedJobsRepository.delete(savedJob);
            if (objectKey != null) {
                registerDeleteAfterCommit(resumeBucket, objectKey);
            }
        });
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new IllegalArgumentException("A valid signed-in user is required");
        }
        return user;
    }

    private String getDescription(SavedJobsEntity job) {
        return job.getJobDescription() == null ? "" : job.getJobDescription();
    }

    private boolean hasSnapshotFor(SavedJobsEntity job, Resume resume) {
        Map<String, Object> snapshot = job.getResumeSnapshot();
        String sourceUpdatedAt = resume.getUpdatedAt() == null ? null : resume.getUpdatedAt().toString();
        String key = snapshotKey(snapshot);
        return resume.getId().equals(job.getResumeId())
                && snapshot != null
                && sourceUpdatedAt != null
                && Objects.equals(snapshot.get("sourceUpdatedAt"), sourceUpdatedAt)
                && key != null
                && key.startsWith("resumes/" + resume.getUserId() + "/saved-job-snapshots/");
    }

    private String createResumeSnapshot(Resume resume, UUID userId) {
        String key = "resumes/" + userId + "/saved-job-snapshots/" + UUID.randomUUID() + ".pdf";
        s3Client.copyObject(CopyObjectRequest.builder()
                .copySource(resume.getS3Bucket() + "/" + resume.getS3Key())
                .destinationBucket(resumeBucket)
                .destinationKey(key)
                .build());
        return key;
    }

    private Map<String, Object> snapshotMetadata(Resume resume, String immutableS3Key) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("resumeId", resume.getId().toString());
        snapshot.put("fileName", resume.getFileName());
        snapshot.put("sourceUpdatedAt", resume.getUpdatedAt().toString());
        snapshot.put("capturedAt", Instant.now().toString());
        snapshot.put("s3Bucket", resumeBucket);
        snapshot.put("s3Key", immutableS3Key);
        return snapshot;
    }

    private String snapshotKey(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        Object key = snapshot.get("s3Key");
        return key instanceof String value && !value.isBlank() ? value : null;
    }

    private void clearComparison(SavedJobsEntity savedJob) {
        savedJob.setMatchScore(null);
        savedJob.setMatchedSkills(null);
        savedJob.setMissingSkills(null);
        savedJob.setRelevantExperience(null);
        savedJob.setOtherGaps(null);
    }

    private JobResultDTO toDto(SavedJobsEntity job) {
        JobResultDTO dto = new JobResultDTO(job.getJobId(), job.getJobTitle(), job.getCompanyName(),
                job.getDateCreated(), job.getLocation(), job.getRedirectUrl(), getDescription(job),
                job.getSalaryMin(), job.getSalaryMax(), true);
        dto.setResumeId(job.getResumeId());
        // Do not expose the private S3 bucket/key to the browser.
        dto.setResumeSnapshot(safeSnapshotMetadata(job.getResumeSnapshot()));
        dto.setMatchScore(job.getMatchScore());
        dto.setMatchedSkills(job.getMatchedSkills());
        dto.setMissingSkills(job.getMissingSkills());
        dto.setRelevantExperience(job.getRelevantExperience());
        dto.setOtherGaps(job.getOtherGaps());
        dto.setComparisonStatus(job.getComparisonStatus());
        dto.setComparisonRequestId(job.getComparisonRequestId());
        return dto;
    }

    private Map<String, Object> safeSnapshotMetadata(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> safe = new HashMap<>();
        safe.put("resumeId", snapshot.get("resumeId"));
        safe.put("fileName", snapshot.get("fileName"));
        safe.put("capturedAt", snapshot.get("capturedAt"));
        return safe;
    }

    private void registerDeleteAfterCommit(String bucket, String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteSnapshotObject(bucket, key);
            }
        });
    }

    private void registerDeleteOnRollback(String bucket, String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteSnapshotObject(bucket, key);
                }
            }
        });
    }

    private void deleteSnapshotObject(String bucket, String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception error) {
            logger.warn("Could not delete resume snapshot object: {}", error.getMessage());
        }
    }
}
