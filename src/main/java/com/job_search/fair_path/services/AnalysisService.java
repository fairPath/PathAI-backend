package com.job_search.fair_path.services;

import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.job_search.fair_path.dataTransferObject.analysis.AnalysisStatusDTO;
import com.job_search.fair_path.dataTransferObject.analysis.AnalyzeRequestDTO;
import com.job_search.fair_path.dataTransferObject.analysis.IssueDetailDTO;
import com.job_search.fair_path.dataTransferObject.analysis.KnownPiiDTO;
import com.job_search.fair_path.dataTransferObject.analysis.ScoresDTO;
import com.job_search.fair_path.entity.Analysis;
import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.AnalysisRepository;
import com.job_search.fair_path.repository.ResumeRepository;

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

    public AnalysisService(AnalysisRepository analysisRepository, ResumeRepository resumeRepository,
            ApplicationEventPublisher events, ObjectMapper objectMapper) {
        this.analysisRepository = analysisRepository;
        this.resumeRepository = resumeRepository;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /** Inserts a PENDING Analysis for the user's active Resume. */
    @Transactional
    public AnalysisStatusDTO requestAnalysis(User user) {
        Resume resume = resumeRepository.findByUserId(user.getId())
                .filter(r -> r.getStatus() == Resume.Status.ACTIVE)
                .orElseThrow(NoActiveResumeException::new);

        Analysis analysis = new Analysis();
        analysis.setResume(resume);
        analysis.setUserId(user.getId());
        analysis.setStatus(Analysis.Status.PENDING);
        analysisRepository.save(analysis);

        events.publishEvent(new AnalysisRequestedEvent(
                new AnalyzeRequestDTO(analysis.getId(), resume.getS3Key(), knownPii(user))));
        return toStatus(analysis);
    }

    /** The user's most recent Analysis, if any. */
    @Transactional(readOnly = true)
    public Optional<AnalysisStatusDTO> latestAnalysis(User user) {
        return analysisRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).map(this::toStatus);
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
