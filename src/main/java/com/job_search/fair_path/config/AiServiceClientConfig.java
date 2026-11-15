package com.job_search.fair_path.config;

import java.io.IOException;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The shared AI-service transport. It owns the base URL, the X-Internal-Token
 * header and the timeouts; each feature puts a thin client on top
 * (ResumeAnalysisAiClient now, JobMatchAiClient in Phase 2).
 *
 * Its default status handler turns any error response into an
 * AiServiceException. A call that sets its own onStatus (JobComparisonClient)
 * takes precedence over it.
 */
@Configuration
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceClientConfig {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Bean
    public RestClient aiServiceRestClient(RestClient.Builder builder, AiServiceProperties properties,
            ObjectMapper objectMapper) {
        Assert.hasText(properties.baseUrl(), "fairpath.ai-service.base-url (FAIRPATH_AI_SERVICE_URL) must be set");
        Assert.hasText(properties.internalToken(),
                "fairpath.ai-service.internal-token (FAIRPATH_AI_INTERNAL_TOKEN) must be set");

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(INTERNAL_TOKEN_HEADER, properties.internalToken())
                .requestFactory(requestFactory)
                .defaultStatusHandler(HttpStatusCode::isError,
                        (request, response) -> {
                            throw toException(response, objectMapper);
                        })
                .build();
    }

    /** An error_code is an upper-case identifier: anything else is never stored in a row. */
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    /**
     * Decodes the error envelope. A valid one has a text correlation_id, an
     * error_code that is an upper-case identifier, and a boolean retryable;
     * anything else yields an exception with no code.
     */
    static AiServiceException toException(ClientHttpResponse response, ObjectMapper objectMapper)
            throws IOException {
        int status = response.getStatusCode().value();
        try {
            JsonNode body = objectMapper.readTree(response.getBody());
            JsonNode correlationId = body == null ? null : body.get("correlation_id");
            JsonNode code = body == null ? null : body.get("error_code");
            JsonNode retryable = body == null ? null : body.get("retryable");
            if (correlationId != null && correlationId.isTextual()
                    && code != null && code.isTextual() && ERROR_CODE.matcher(code.asText()).matches()
                    && retryable != null && retryable.isBoolean()) {
                return new AiServiceException(status, correlationId.asText(), code.asText(), retryable.asBoolean());
            }
        } catch (IOException | RuntimeException e) {
            // Not JSON, or not readable: treated as no envelope. Never log the body.
        }
        return new AiServiceException(status, null, null, false);
    }
}
