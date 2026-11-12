package com.job_search.fair_path.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * The shared AI-service transport. It owns the base URL, the X-Internal-Token
 * header and the timeouts; each feature puts a thin client on top
 * (ResumeAnalysisAiClient now, JobMatchAiClient in Phase 2).
 */
@Configuration
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceClientConfig {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Bean
    public RestClient aiServiceRestClient(RestClient.Builder builder, AiServiceProperties properties) {
        Assert.hasText(properties.baseUrl(), "fairpath.ai-service.base-url (AI_SERVICE_URL) must be set");
        Assert.hasText(properties.internalToken(),
                "fairpath.ai-service.internal-token (AI_INTERNAL_TOKEN) must be set");

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(INTERNAL_TOKEN_HEADER, properties.internalToken())
                .requestFactory(requestFactory)
                .build();
    }
}
