package com.job_search.fair_path.services;

import java.time.Duration;
import java.time.Instant;

/**
 * One feature's stranded work. Every target follows the same two rules: waited
 * too long without starting (pending) and ran too long (processing). Each
 * brings its own guarded UPDATE; the runner owns the schedule, the
 * transaction, the boot check and the logging.
 */
public interface SweepTarget {

    /** Shown in logs. */
    String name();

    Duration pendingStaleAfter();

    Duration processingStaleAfter();

    /** Threads of the pool this target's work runs on. */
    int poolThreads();

    /** How many requests may wait in that pool's queue. */
    int poolQueueCapacity();

    /** Times out every stale row as of {@code now}; returns how many. Runs in the runner's transaction. */
    int sweep(Instant now);
}
