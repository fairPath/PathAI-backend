package com.job_search.fair_path.services;

import com.job_search.fair_path.exceptions.SavedJobLimitExceededException;

import com.job_search.fair_path.exceptions.ComparisonFailure;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.job_search.fair_path.dataTransferObject.JobResultDTO;
import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.entity.SavedJobsEntity;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.ResumeRepository;
import com.job_search.fair_path.repository.SavedJobsRepository;
import com.job_search.fair_path.repository.UserRepository;


@Service
public class SavedJobService {
    private static final int MAX_SAVED_JOBS = 150;

    private final SavedJobsRepository savedJobsRepository;
    private final UserRepository userRepository;
    private final ResumeRepository resumeRepository;

    public SavedJobService(
            SavedJobsRepository savedJobsRepository,
            UserRepository userRepository,
            ResumeRepository resumeRepository) {
        this.savedJobsRepository = savedJobsRepository;
        this.userRepository = userRepository;
        this.resumeRepository = resumeRepository;
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
        String description = request.getJobDescription() == null ? "" : request.getJobDescription();

        savedJob.setCompanyName(request.getCompanyName());
        savedJob.setJobTitle(request.getTitle());
        savedJob.setRedirectUrl(request.getRedirectUrl());
        savedJob.setDateCreated(request.getDateCreated());
        savedJob.setLocation(request.getLocation());
        savedJob.setSalaryMin(request.getSalaryMin());
        savedJob.setSalaryMax(request.getSalaryMax());
        savedJob.setJobDescription(description);

        boolean changed = !Objects.equals(previousDescription, description);
        boolean existing = savedJob.getId() != null;
        String expectedStatus = savedJob.getComparisonStatus();
        if (!existing) savedJob.setComparisonStatus("NOT_REQUESTED");
        savedJob = savedJobsRepository.saveAndFlush(savedJob);
        if (existing && changed) {
            savedJobsRepository.changeComparison(savedJob.getId(), userId, expectedStatus,
                    "NOT_REQUESTED", null);
            savedJob = savedJobsRepository.findById(savedJob.getId()).orElseThrow();
        }
        return toDto(savedJob);
    }

    /** An explicit user action, scoped by JWT. Saving never queues a comparison. */
    @Transactional
    public JobResultDTO requestComparison(String jobId, Authentication authentication) {
        UUID userId = authenticatedUser(authentication).getId();
        SavedJobsEntity job = savedJobsRepository.findForUpdate(userId, jobId)
                .orElseThrow(() -> new ComparisonFailure("JOB_NOT_FOUND", false));
        if ("PENDING".equals(job.getComparisonStatus())) return toDto(job);
        resumeRepository.findByUserId(userId).filter(r -> r.getStatus() == Resume.Status.ACTIVE)
                .orElseThrow(() -> new ComparisonFailure("RESUME_REQUIRED", false));
        int changed = savedJobsRepository.changeComparison(job.getId(), userId, job.getComparisonStatus(),
                "PENDING", UUID.randomUUID());
        if (changed != 1) throw new ComparisonFailure("COMPARISON_IN_PROGRESS", true);
        return toDto(savedJobsRepository.findById(job.getId()).orElseThrow());
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
            savedJobsRepository.delete(savedJob);
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

    private JobResultDTO toDto(SavedJobsEntity job) {
        JobResultDTO dto = new JobResultDTO(job.getJobId(), job.getJobTitle(), job.getCompanyName(),
                job.getDateCreated(), job.getLocation(), job.getRedirectUrl(), getDescription(job),
                job.getSalaryMin(), job.getSalaryMax(), true);
        dto.setResumeId(job.getResumeId());
        dto.setMatchScore(job.getMatchScore());
        dto.setMatchedSkills(job.getMatchedSkills());
        dto.setMissingSkills(job.getMissingSkills());
        dto.setRelevantExperience(job.getRelevantExperience());
        dto.setOtherGaps(job.getOtherGaps());
        dto.setComparisonStatus(job.getComparisonStatus());
        dto.setComparisonRequestId(job.getComparisonRequestId());
        dto.setComparisonResumeUpdatedAt(job.getComparisonResumeUpdatedAt());
        dto.setComparisonCompletedAt(job.getComparisonCompletedAt());
        return dto;
    }

}
