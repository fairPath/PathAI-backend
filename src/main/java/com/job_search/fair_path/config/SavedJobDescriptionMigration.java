package com.job_search.fair_path.config;

import java.util.List;
import javax.sql.DataSource;

import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Boot runs this initializer before Hibernate and the worker access the new column layout. */
@Configuration(proxyBeanMethods = false)
@Import(DatabaseInitializationDependencyConfigurer.class)
public class SavedJobDescriptionMigration {
    @Bean
    DataSourceScriptDatabaseInitializer savedJobDescriptionInitializer(DataSource dataSource) {
        var settings = new DatabaseInitializationSettings();
        settings.setMode(DatabaseInitializationMode.ALWAYS);
        settings.setSchemaLocations(List.of("classpath:db/migration/20261112_single_job_description.sql"));
        // Keep the PostgreSQL DO block as one statement, including its internal semicolons.
        settings.setSeparator(";;");
        return new DataSourceScriptDatabaseInitializer(dataSource, settings);
    }
}
