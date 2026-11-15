package com.job_search.fair_path.exceptions;

import java.time.Instant;

/** The user's last completed Analysis is still inside the Cooldown. */
public class CooldownActiveException extends RuntimeException {

    private final Instant retryAt;

    public CooldownActiveException(Instant retryAt) {
        super("Cooldown is active");
        this.retryAt = retryAt;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
