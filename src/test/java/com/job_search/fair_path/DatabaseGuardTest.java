package com.job_search.fair_path;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import com.job_search.fair_path.support.IntegrationTestBase;

class DatabaseGuardTest extends IntegrationTestBase {

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

    // The local .env file is imported as its own property source, so check for
    // that source rather than for DB_URL itself: DB_URL may legitimately be
    // exported in the shell (system environment) without .env being loaded.
    // Only source names are inspected, never values.
    @Test
    void localEnvFileIsNotLoaded() {
        List<String> envFileSources = ((ConfigurableEnvironment) environment).getPropertySources().stream()
                .map(PropertySource::getName)
                .filter(name -> name.contains("[.env"))
                .toList();
        assertThat(envFileSources)
                .as("a local .env property source is loaded; the import must be skipped under the test profile")
                .isEmpty();
    }
}
