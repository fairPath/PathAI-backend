package com.job_search.fair_path.services;

/** Analyze was requested but the user has no ACTIVE (upload-confirmed) Resume. */
public class NoActiveResumeException extends RuntimeException {
    public NoActiveResumeException() {
        super("No active resume to analyze");
    }
}
