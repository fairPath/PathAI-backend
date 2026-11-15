package com.job_search.fair_path.exceptions;

/** Safe, typed comparison failure; never carries resume text or upstream bodies. */
public class ComparisonFailure extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;
    private final long retryAfterSeconds;
    public ComparisonFailure(String errorCode, boolean retryable) {
        this(errorCode, retryable, 0);
    }
    public ComparisonFailure(String errorCode, boolean retryable, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
    public long retryAfterSeconds() { return retryAfterSeconds; }
    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
}
