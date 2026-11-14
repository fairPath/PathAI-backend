package com.job_search.fair_path.config;

import java.io.IOException;

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

    /** Decodes the error envelope; a body that isn't a valid one yields an exception with no code. */
    static AiServiceException toException(ClientHttpResponse response, ObjectMapper objectMapper)
            throws IOException {
        int status = response.getStatusCode().value();
        try {
            JsonNode body = objectMapper.readTree(response.getBody());
            JsonNode code = body == null ? null : body.get("error_code");
            if (code != null && code.isTextual() && !code.asText().isBlank()) {
                JsonNode correlationId = body.get("correlation_id");
                JsonNode retryable = body.get("retryable");
                return new AiServiceException(status,
                        correlationId != null && correlationId.isTextual() ? correlationId.asText() : null,
                        code.asText(),
                        retryable != null && retryable.asBoolean(false));
            }
        } catch (IOException | RuntimeException e) {
            // Not JSON, or not readable: treated as no envelope. Never log the body.
        }
        return new AiServiceException(status, null, null, false);
    }
}
