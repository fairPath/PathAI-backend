package com.job_search.fair_path.services;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

/**
 * Handles the Analyze request and the status read. The AI-service call itself
 * happens after the insert commits (see AnalysisRunner).
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
    private final AnalysisProperties properties;
    private final Clock clock;

    public AnalysisService(AnalysisRepository analysisRepository, ResumeRepository resumeRepository,
            ApplicationEventPublisher events, ObjectMapper objectMapper,
            UsageRecordRepository usageRecordRepository, AnalysisProperties properties, Clock clock) {
        this.analysisRepository = analysisRepository;
        this.resumeRepository = resumeRepository;
        this.events = events;
        this.objectMapper = objectMapper;
        this.usageRecordRepository = usageRecordRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** PENDING and PROCESSING: the statuses of an Analysis that is still running. */
    public static final Set<Analysis.Status> IN_FLIGHT = EnumSet.of(Analysis.Status.PENDING,
            Analysis.Status.PROCESSING);

    /**
     * Inserts a PENDING Analysis for the user's active Resume. In-progress is
     * checked first, then the Cooldown. The pre-check answers the normal case;
     * the partial unique index settles a true race, and both end the same way.
     */
    @Transactional
    public AnalysisStatusDTO requestAnalysis(User user) {
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
