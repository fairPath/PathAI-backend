package com.job_search.fair_path.services;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.job_search.fair_path.dataTransferObject.ResumePresignUrlResponseDTO;
import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.repository.AnalysisRepository;
import com.job_search.fair_path.repository.ResumeRepository;
import com.job_search.fair_path.repository.UserRepository;

import jakarta.transaction.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import com.job_search.fair_path.exceptions.AnalysisInProgressException;

@Service
public class ResumeService {
    private static final String UPLOAD_PLACEHOLDER = "resume-upload-placeholder";
    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final String bucketName;
    private final ResumeRepository resumeRepository;
    private final AnalysisRepository analysisRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public ResumeService(S3Presigner s3Presigner, S3Client s3Client, @Value("${aws.s3.bucket}") String bucketName,
            ResumeRepository resumeRepository, AnalysisRepository analysisRepository, UserRepository userRepository, Clock clock) {

        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.resumeRepository = resumeRepository;
        this.analysisRepository = analysisRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    private String currentUploadETag(String key) {
        try {
            // A recreated placeholder must have a different ETag from the deleted object.
            return s3Client.putObject(request -> request.bucket(bucketName).key(key).ifNoneMatch("*")
                    .metadata(Map.of(UPLOAD_PLACEHOLDER, "true")),
                    RequestBody.fromString(UUID.randomUUID().toString())).eTag();
        } catch (S3Exception failure) {
            if (failure.statusCode() != 412) throw failure;
            return s3Client.headObject(request -> request.bucket(bucketName).key(key)).eTag();
        }
    }

    private static final Duration UPLOAD_URL_DURATION = Duration.ofMinutes(15);

    @Transactional
    public ResumePresignUrlResponseDTO createPresignUploadUrl(UUID userId, String filename) {
        // Implementation for generating presigned upload URL
        if (userId == null) {
            throw new IllegalArgumentException("User ID cannot be null");
        }
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }
        // Replacing deletes the Analyses, and deleting an in-flight one would
        // discard its result with no Usage record. Checked before the try
        // below, which would wrap this exception as a 500.
        var user = requireActiveUserForUpdate(userId);
        rejectWhileAnalysisInFlight(userId);

        try {

            String key = "resumes/" + userId + "/" + "active.pdf";
            Resume resume = resumeRepository.findByUserId(userId).orElseGet(Resume::new); // if no resume exists, create
                                                                                          // a new one else update
                                                                                          // existing
            resume.setUserId(userId);
            resume.setS3Bucket(bucketName);
            resume.setS3Key(key);
            resume.setFileName(filename);
            resume.setStatus(Resume.Status.PENDING);
            resumeRepository.save(resume);

            // A conditional PUT cannot recreate this key once deletion removes it.
            String etag = currentUploadETag(key);
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/pdf")
                    .overrideConfiguration(config -> config.putHeader("If-Match", etag))
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(UPLOAD_URL_DURATION)
                    .putObjectRequest(putObjectRequest)
                    .build();

            var signed = s3Presigner.presignPutObject(presignRequest);
            if (signed.signedHeaders().keySet().stream().noneMatch("If-Match"::equalsIgnoreCase)) {
                throw new IllegalStateException("Upload URL must sign the If-Match header");
            }
            String presignedUrl = signed.url().toString();
            user.setResumeUploadExpiresAt(clock.instant().plus(UPLOAD_URL_DURATION));
            return new ResumePresignUrlResponseDTO(resume.getId(), presignedUrl, etag);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL", e);
        }

    }

    @Transactional
    public void deleteResume(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID cannot be null");
        }
        lockUserThenRejectWhileAnalysisInFlight(userId);
        Resume resume = resumeRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Resume not found for user id: " + userId));

        s3Client.deleteObject(b -> b.bucket(resume.getS3Bucket()).key(resume.getS3Key()));
        resumeRepository.delete(resume);
    }

    /**
     * For the steps that destroy data (delete, confirm). Takes the same per-user
     * lock as requestAnalysis, held until commit, so an Analyze cannot start
     * between this check and the delete. Must run inside a transaction.
     */
    private void lockUserThenRejectWhileAnalysisInFlight(UUID userId) {
        requireActiveUserForUpdate(userId);
        rejectWhileAnalysisInFlight(userId);
    }

    private com.job_search.fair_path.entity.User requireActiveUserForUpdate(UUID userId) {
        return userRepository.findByIdForUpdate(userId)
                .filter(com.job_search.fair_path.entity.User::isEnabled)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Account unavailable"));
    }

    private void rejectWhileAnalysisInFlight(UUID userId) {
        if (analysisRepository.existsByUserIdAndStatusIn(userId, AnalysisService.IN_FLIGHT)) {
            throw new AnalysisInProgressException();
        }
    }

    @Transactional
    public String getPresignedDownloadUrl(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID cannot be null");
        }
        String key = "resumes/" + userId + "/" + "active.pdf";
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(objectRequest)
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    @Transactional
    public void confirmUpload(UUID resumeId, UUID userId) {
        if (resumeId == null) {
            throw new IllegalArgumentException("Resume ID cannot be null");
        }
        if (userId == null) {
            throw new IllegalArgumentException("User ID cannot be null");
        }

        try {
            //validate pdf exists in s3 before confirming in db
            
            Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Resume not found for resume id: " + resumeId + " and user id: " + userId));
             HeadObjectResponse head = s3Client.headObject(b -> b.bucket(resume.getS3Bucket()).key(resume.getS3Key()));
            if (head.contentLength() <= 0 || head.metadata().containsKey(UPLOAD_PLACEHOLDER)) {
                throw new IllegalStateException("Uploaded resume file is empty");
            }
            
            // An Analyze that slipped in after the presign check is still running:
            // deleting it would discard its result with no Usage record.
            lockUserThenRejectWhileAnalysisInFlight(userId);

            // Guarded PENDING → ACTIVE. Only the call that really activates a new
            // file deletes the old Analyses; a duplicate confirm changes nothing,
            // so it cannot delete results made since. The Usage record is kept.
            int activated = resumeRepository.changeStatus(resume.getId(), userId, Resume.Status.PENDING,
                    Resume.Status.ACTIVE, clock.instant());
            if (activated == 1) {
                analysisRepository.deleteAllForUser(userId);
            }
        } catch (S3Exception e) {
            throw new RuntimeException("Failed to confirm resume upload", e);
        }

    }

}
