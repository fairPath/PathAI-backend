package com.job_search.fair_path.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SavedJobDescriptionMigrationTests {
    @Test
    void bootExecutesTheEntireMigrationBlockAsOneStatement() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getAutoCommit()).thenReturn(true);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> dataSource);
            context.register(SavedJobDescriptionMigration.class);
            context.refresh();
            var sql = ArgumentCaptor.forClass(String.class);
            verify(statement, times(1)).execute(sql.capture());
            assertTrue(sql.getValue().startsWith("DO $migration$"));
            assertTrue(sql.getValue().contains("END; $migration$"));
            assertTrue(sql.getValue().contains("DROP COLUMN IF EXISTS job_description3"));
            assertTrue(sql.getValue().contains("ALTER COLUMN job_description TYPE text"));
        }
    }
}
