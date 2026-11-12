package com.job_search.fair_path.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Throwaway Postgres for integration tests.
 *
 * Declared as a Spring bean (not a static @Container field) so Spring owns the
 * lifecycle and the container lives as long as the cached application context.
 * @ServiceConnection hands the container's JDBC details to the datasource,
 * overriding any spring.datasource.* properties.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16");
    }
}
