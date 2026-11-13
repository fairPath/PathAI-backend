package com.job_search.fair_path.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.job_search.fair_path.dataTransferObject.analysis.AnalysisStatusDTO;
import com.job_search.fair_path.dataTransferObject.analysis.ErrorEnvelopeDTO;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.services.AnalysisService;
import com.job_search.fair_path.services.NoActiveResumeException;

/** User-initiated Analysis of the user's Resume: 202 + polling. */
@RestController
@RequestMapping("/resumes")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalysisStatusDTO> analyze(Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(analysisService.requestAnalysis(user));
    }

    /** The latest Analysis; 204 when the user has never analyzed. */
    @GetMapping("/analysis")
    public ResponseEntity<AnalysisStatusDTO> latestAnalysis(Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return analysisService.latestAnalysis(user)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @ExceptionHandler(NoActiveResumeException.class)
    public ResponseEntity<ErrorEnvelopeDTO> noActiveResume() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorEnvelopeDTO(null, "RESUME_NOT_FOUND", false));
    }
}
