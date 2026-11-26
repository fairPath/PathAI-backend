package com.job_search.fair_path.services;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.job_search.fair_path.config.AiExecutionProperties;
import com.job_search.fair_path.config.AnalysisProperties;
import com.job_search.fair_path.repository.AnalysisRepository;

/** PENDING (from created_at) and PROCESSING (from started_at) Analyses past their thresholds. */
@Component
public class AnalysisSweepTarget implements SweepTarget {
    private final AnalysisRepository repository;
    private final AnalysisProperties properties;
    private final AiExecutionProperties pool;

    public AnalysisSweepTarget(AnalysisRepository repository, AnalysisProperties properties,
            AiExecutionProperties pool) {
        this.repository = repository;
        this.properties = properties;
        this.pool = pool;
    }

    @Override public String name() { return "analysis"; }
    @Override public Duration pendingStaleAfter() { return properties.pendingStaleAfter(); }
    @Override public Duration processingStaleAfter() { return properties.processingStaleAfter(); }
    @Override public int poolThreads() { return pool.analysisThreads(); }
    @Override public int poolQueueCapacity() { return pool.analysisQueueCapacity(); }

    @Override
    public int sweep(Instant now) {
        return repository.timeOutStale(AnalysisErrorCodes.ANALYSIS_TIMED_OUT,
                now.minus(pendingStaleAfter()), now.minus(processingStaleAfter())).size();
    }
}
