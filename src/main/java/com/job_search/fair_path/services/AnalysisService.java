package com.job_search.fair_path.services;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.config.AnalysisProperties;
import com.job_search.fair_path.dataTransferObject.analysis.AnalysisStatusDTO;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;
import com.job_search.fair_path.dataTransferObject.analysis.IssueDetailDTO;
import com.job_search.fair_path.dataTransferObject.analysis.KnownPiiDTO;
import com.job_search.fair_path.dataTransferObject.analysis.ScoresDTO;
import com.job_search.fair_path.entity.Analysis;
import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.entity.UsageRecord;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.AnalysisRepository;
import com.job_search.fair_path.repository.ResumeRepository;
import com.job_search.fair_path.repository.UsageRecordRepository;
import com.job_search.fair_path.repository.UserRepository;
import com.job_search.fair_path.exceptions.NoActiveResumeException;
import com.job_search.fair_path.exceptions.AnalysisInProgressException;
import com.job_search.fair_path.exceptions.CooldownActiveException;

/**
 * Handles the Analyze request, the status read, and the transition to
 * COMPLETED. The AI-service call itself happens after the insert commits (see
 * AnalysisRunner), which then calls back into completeAnalysis.
 */
@Service
public class AnalysisService {

    private static final TypeReference<List<IssueDetailDTO>> ISSUE_LIST = new TypeReference<>() {
    };

    private final AnalysisRepository analysisRepository;
    private final ResumeRepository resumeRepository;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final UsageRecordRepository usageRecordRepository;
    private final UserRepository userRepository;
    private final AnalysisProperties properties;
    private final Clock clock;

    public AnalysisService(AnalysisRepository analysisRepository, ResumeRepository resumeRepository,
            ApplicationEventPublisher events, ObjectMapper objectMapper,
            UsageRecordRepository usageRecordRepository, UserRepository userRepository,
            AnalysisProperties properties, Clock clock) {
        this.analysisRepository = analysisRepository;
        this.resumeRepository = resumeRepository;
        this.events = events;
        this.objectMapper = objectMapper;
        this.usageRecordRepository = usageRecordRepository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** PENDING and PROCESSING: the statuses of an Analysis that is still running. */
    public static final Set<Analysis.Status> IN_FLIGHT = EnumSet.of(Analysis.Status.PENDING,
            Analysis.Status.PROCESSING);

    /**
     * Inserts a PENDING Analysis for the user's active Resume. It first locks
     * the user's row, held until commit, so the checks and the insert cannot
     * interleave with another request from the same user. In-progress is
     * checked first, then the Cooldown. The pre-check answers the normal case;
     * the partial unique index settles a true race, and both end the same way.
     */
    @Transactional
    public AnalysisStatusDTO requestAnalysis(User user) {
        // One request per user at a time: the checks below and the insert must
        // not interleave with another Analyze, or with a Resume delete/confirm.
        userRepository.findByIdForUpdate(user.getId()).filter(User::isEnabled)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Account unavailable"));
        Resume resume = resumeRepository.findByUserId(user.getId())
                .filter(r -> r.getStatus() == Resume.Status.ACTIVE)
                .orElseThrow(NoActiveResumeException::new);

        if (analysisRepository.existsByUserIdAndStatusIn(user.getId(), IN_FLIGHT)) {
            throw new AnalysisInProgressException();
        }
        usageRecordRepository.latestOccurredAt(user.getId(), UsageRecord.Kind.ANALYSIS_COMPLETED)
                .map(last -> last.plus(properties.cooldown()))
                .filter(retryAt -> retryAt.isAfter(clock.instant()))
                .ifPresent(retryAt -> {
                    throw new CooldownActiveException(retryAt);
                });

        Analysis analysis = new Analysis();
        analysis.setResume(resume);
        analysis.setUserId(user.getId());
        analysis.setStatus(Analysis.Status.PENDING);
        try {
            analysisRepository.saveAndFlush(analysis);
        } catch (DataIntegrityViolationException e) {
            // Two requests passed the pre-check together; the index let one in.
            // The transaction is aborted now, so do not query again.
            if (violatesOneInFlightIndex(e)) {
                throw new AnalysisInProgressException();
            }
            throw e;
        }

        events.publishEvent(new AnalysisRequestedEvent(
                new AnalyzeRequestDTO(analysis.getId(), resume.getS3Key(), knownPii(user))));
        return toStatus(analysis);
    }

    /**
     * PROCESSING → COMPLETED and its Usage record, in one transaction. Called
     * by AnalysisRunner after the AI call, so the call crosses a bean boundary
     * and Spring's transaction proxy applies. It stays short on purpose: the
     * AI call happens before it, never inside it.
     *
     * Returns the rows the guarded update changed. The Usage record is written
     * only when that is 1, so a late result counts for nothing. If the insert
     * throws, the update rolls back with it.
     */
    @Transactional
    public int completeAnalysis(UUID id, ScoresDTO scores, String issuesJson, String model,
            String pipelineVersion) {
        UUID userId = analysisRepository.findUserId(id);
        if (userId == null || userRepository.findByIdForUpdate(userId).filter(User::isEnabled).isEmpty()) {
            return 0;
        }
        int rows = analysisRepository.complete(id, scores, issuesJson, model, pipelineVersion);
        if (rows == 1) {
            usageRecordRepository.save(new UsageRecord(userRepository.getReferenceById(userId),
                    UsageRecord.Kind.ANALYSIS_COMPLETED, clock.instant()));
        }
        return rows;
    }

    /** The user's most recent Analysis, if any. */
    @Transactional(readOnly = true)
    public Optional<AnalysisStatusDTO> latestAnalysis(User user) {
        return analysisRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).map(this::toStatus);
    }

    /** Whether this failed insert broke the one-in-flight index (matched by constraint name). */
    public static boolean violatesOneInFlightIndex(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && Analysis.ONE_IN_FLIGHT_INDEX.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private static KnownPiiDTO knownPii(User user) {
        String fullName = String.join(" ",
                nullToEmpty(user.getFirstName()), nullToEmpty(user.getLastName())).trim();
        // The user record holds no phone number yet.
        return new KnownPiiDTO(fullName.isEmpty() ? null : fullName, user.getEmail(), null);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private AnalysisStatusDTO toStatus(Analysis analysis) {
        boolean completed = analysis.getStatus() == Analysis.Status.COMPLETED;
        return new AnalysisStatusDTO(
                analysis.getId(),
                analysis.getStatus(),
                analysis.getCreatedAt(),
                analysis.getErrorCode(),
                analysis.getStatus() == Analysis.Status.FAILED
                        ? AnalysisErrorCodes.isRetryable(analysis.getErrorCode()) : null,
                completed ? new ScoresDTO(analysis.getScoreFormatting(), analysis.getScoreParseability(),
                        analysis.getScoreWriting(), analysis.getScoreOverall()) : null,
                completed ? readIssues(analysis.getIssues()) : null,
                analysis.getModel(),
                analysis.getPipelineVersion());
    }

    private List<IssueDetailDTO> readIssues(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ISSUE_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored Issues are not valid JSON", e);
        }
    }
}
