package com.job_search.fair_path.exceptions;

/** Safe rejection of an explicit comparison request. */
public class ComparisonFailure extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;
    public ComparisonFailure(String errorCode, boolean retryable) {
        super(errorCode);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
}
