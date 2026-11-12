package com.job_search.fair_path.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Base class for integration tests. Every subclass shares one Spring context
 * (and therefore one Postgres container) because the configuration is
 * identical: a different set of @MockitoBean / @Import / properties would
 * create a second context and start a second container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@ActiveProfiles("test")
// Every test starts from empty application tables; see sql/reset.sql for why TRUNCATE.
@Sql(scripts = "/sql/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class IntegrationTestBase {

    // S3 is an external boundary: never reach real AWS from tests.
    @MockitoBean
    protected S3Client s3Client;

    @MockitoBean
    protected S3Presigner s3Presigner;
}
