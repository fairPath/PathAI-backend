package com.job_search.fair_path.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The app clock behind every time the limits compare, so tests can move it. */
@Configuration
@EnableConfigurationProperties(AnalysisProperties.class)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
