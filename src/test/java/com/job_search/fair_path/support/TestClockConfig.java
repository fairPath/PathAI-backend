package com.job_search.fair_path.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the app clock in integration tests. Imported by IntegrationTestBase
 * so every test class still shares one Spring context and one Postgres.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
