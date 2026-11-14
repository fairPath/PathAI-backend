package com.job_search.fair_path.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.job_search.fair_path.dataTransferObject.analysis.ErrorEnvelopeDTO;
import com.job_search.fair_path.services.AnalysisInProgressException;
import com.job_search.fair_path.services.CooldownActiveException;

/**
 * Maps the Analysis limits to the shared envelope. Scoped to the two
 * controllers that can hit them (Analyze, and Resume replace and delete).
 * Both are 429: a 409 would be rewritten by the frontend's shared API helper.
 */
@ControllerAdvice(assignableTypes = { AnalysisController.class, ResumeController.class })
public class AnalysisLimitExceptionHandler {

    @ExceptionHandler(AnalysisInProgressException.class)
    public ResponseEntity<ErrorEnvelopeDTO> inProgress() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorEnvelopeDTO(null, "ANALYSIS_IN_PROGRESS", true));
    }

    @ExceptionHandler(CooldownActiveException.class)
    public ResponseEntity<ErrorEnvelopeDTO> cooldownActive(CooldownActiveException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorEnvelopeDTO(null, "COOLDOWN_ACTIVE", true, e.retryAt()));
    }
}
