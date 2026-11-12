package com.job_search.fair_path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Shows the stub mechanism for outbound AI-service calls: a real HTTP server on
 * a random port that a Spring RestClient talks to, with the request recorded so
 * the test can assert on what was sent and can script failures.
 *
 * The production aiServiceRestClient bean does not exist yet (that is the
 * walking-skeleton ticket), so this uses a test-local RestClient. When the
 * bean lands, point its base URL at {@code aiService.baseUrl()} instead.
 */
class AiServiceStubTest {

    @RegisterExtension
    static WireMockExtension aiService = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final RestClient client = RestClient.builder()
            .baseUrl(aiService.baseUrl())
            .defaultHeader("X-Internal-Token", "test-token")
            .build();

    @Test
    void stubReturnsAScriptedResponseAndRecordsTheRequest() {
        aiService.stubFor(post(urlEqualTo("/resume"))
                .withHeader("X-Internal-Token", equalTo("test-token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"model\":\"stub-model\"}")));

        String body = client.post()
                .uri("/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"analysis_id\":\"a-1\",\"s3_key\":\"resumes/x/active.pdf\",\"user_id\":\"u-1\"}")
                .retrieve()
                .body(String.class);

        assertThat(body).contains("stub-model");
        aiService.verify(postRequestedFor(urlEqualTo("/resume"))
                .withRequestBody(equalToJson("{\"analysis_id\":\"a-1\",\"s3_key\":\"resumes/x/active.pdf\",\"user_id\":\"u-1\"}")));
    }

    @Test
    void stubCanScriptAnAiServiceFailure() {
        aiService.stubFor(post(urlEqualTo("/resume"))
                .willReturn(aResponse().withStatus(504)));

        assertThatThrownBy(() -> client.post().uri("/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpServerErrorException.GatewayTimeout.class);
    }
}
