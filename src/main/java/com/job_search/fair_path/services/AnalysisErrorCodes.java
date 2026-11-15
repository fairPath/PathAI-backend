package com.job_search.fair_path.services;

/**
 * Which stored error_codes a retry can never fix. Anything not listed is
 * retryable: a missing Retry button on a transient failure blocks the user,
 * while a Retry on a permanent one only costs a click.
 */
public final class AnalysisErrorCodes {

    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    static final String ANALYSIS_TIMED_OUT = "ANALYSIS_TIMED_OUT";

    private AnalysisErrorCodes() {
    }

    public static boolean isRetryable(String errorCode) {
        return AiServiceErrors.isRetryableAnalysisCode(errorCode);
    }
}
