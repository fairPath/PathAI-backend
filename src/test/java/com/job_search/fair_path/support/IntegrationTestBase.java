package com.job_search.fair_path.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

import com.github.tomakehurst.wiremock.WireMockServer;

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

    public static final String AI_SERVICE_TOKEN = "test-internal-token";

    /**
     * Stands in for the AI service at the aiServiceRestClient HTTP boundary.
     * One static server for the whole run, so every subclass registers the same
     * base URL and keeps sharing one context.
     */
    protected static final WireMockServer aiService = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        aiService.start();
        Runtime.getRuntime().addShutdownHook(new Thread(aiService::stop));
    }

    @DynamicPropertySource
    static void aiServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("fairpath.ai-service.base-url", aiService::baseUrl);
        registry.add("fairpath.ai-service.internal-token", () -> AI_SERVICE_TOKEN);
    }

    // S3 is an external boundary: never reach real AWS from tests.
    @MockitoBean
    protected S3Client s3Client;

    @MockitoBean
    protected S3Presigner s3Presigner;

    @BeforeEach
    void resetAiServiceStub() {
        aiService.resetAll();
    }
}
