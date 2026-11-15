package com.job_search.fair_path.config;

/**
 * An error response from the AI service. When the body was a valid error
 * envelope its fields are set; otherwise (a proxy's 503, FastAPI's own 401,
 * an unhandled 500) errorCode is null.
 *
 * The message carries the status and code only: response bodies can hold
 * Resume content and must never reach logs or the browser.
 */
public class AiServiceException extends RuntimeException {

    private final int httpStatus;
    private final String correlationId;
    private final String errorCode;
    private final boolean retryable;

    public AiServiceException(int httpStatus, String correlationId, String errorCode, boolean retryable) {
        super("AI service returned HTTP " + httpStatus + (errorCode == null ? "" : " error_code=" + errorCode));
        this.httpStatus = httpStatus;
        this.correlationId = correlationId;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** The envelope's correlation_id; null when there was no valid envelope or it carried none. */
    public String correlationId() {
        return correlationId;
    }

    /** The envelope's error_code; null when the response had no valid envelope. */
    public String errorCode() {
        return errorCode;
    }

    /** What the AI service said. The stored retryability comes from the code, see AnalysisErrorCodes. */
    public boolean retryable() {
        return retryable;
    }

    public boolean hasEnvelope() {
        return errorCode != null;
    }
}
