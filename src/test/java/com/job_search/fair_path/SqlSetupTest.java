package com.job_search.fair_path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import com.job_search.fair_path.support.IntegrationTestBase;

/**
 * Shows the harness can run hand-written SQL in test setup, and that real
 * Postgres enforces a partial unique index (which an in-memory database or
 * ddl-auto=update would not give us).
 */
class SqlSetupTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Sql("/sql/scratch-partial-unique-index.sql")
    void partialUniqueIndexAllowsOneInFlightRowPerUser() {
        UUID userId = UUID.randomUUID();
        insert(userId, "IN_FLIGHT");

        // finished rows are outside the index predicate, so any number are allowed
        insert(userId, "DONE");
        insert(userId, "DONE");

        // a second in-flight row for the same user violates the partial index
        assertThatThrownBy(() -> insert(userId, "IN_FLIGHT"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // another user may still have their own in-flight row
        insert(UUID.randomUUID(), "IN_FLIGHT");

        assertThat(jdbc.queryForObject("select count(*) from scratch_in_flight", Integer.class)).isEqualTo(4);
    }

    private void insert(UUID userId, String status) {
        jdbc.update("insert into scratch_in_flight (user_id, status) values (?, ?)", userId, status);
    }
}
