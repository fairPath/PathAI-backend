package com.job_search.fair_path.services;

/** The user already has an Analysis PENDING or PROCESSING. */
public class AnalysisInProgressException extends RuntimeException {
    public AnalysisInProgressException() {
        super("An Analysis is already in progress");
    }
}
