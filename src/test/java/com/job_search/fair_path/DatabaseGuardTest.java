package com.job_search.fair_path;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import com.job_search.fair_path.support.PostgresTestConfig;

@SpringBootTest
@Import(PostgresTestConfig.class)
@ActiveProfiles("test")
class DatabaseGuardTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void dataSourcePointsToTestDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            assertThat(url).doesNotContain("supabase");
            assertThat(url).contains("localhost");
        }
    }

    // DB_URL is defined only in the developer's local .env file, so if it is
    // visible here the .env import was not switched off for tests.
    // Assert on presence only: a failure message must never print the value.
    @Test
    void localEnvFileIsNotLoaded() {
        assertThat(environment.containsProperty("DB_URL"))
                .as("DB_URL (defined only in the local .env) is visible to the test context")
                .isFalse();
    }
}
