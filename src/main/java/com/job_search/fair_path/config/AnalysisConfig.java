package com.job_search.fair_path.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Analysis limits: their config, and the app clock they compare against (so tests can move it). */
@Configuration
@EnableConfigurationProperties(AnalysisProperties.class)
public class AnalysisConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
