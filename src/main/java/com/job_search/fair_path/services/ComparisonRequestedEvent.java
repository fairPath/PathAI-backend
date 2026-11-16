package com.job_search.fair_path.services;

import java.util.UUID;

/** Dispatch only after the guarded PENDING transition commits. */
public record ComparisonRequestedEvent(UUID requestId) {}
