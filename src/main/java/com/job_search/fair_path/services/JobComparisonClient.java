package com.job_search.fair_path.services;

import com.job_search.fair_path.exceptions.ComparisonFailure;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Thin job-comparison client over the shared aiServiceRestClient (URL, token and timeouts live there). */
@Component
public class JobComparisonClient {
    private final ObjectMapper mapper;
    private final RestClient aiServiceRestClient;

    public JobComparisonClient(ObjectMapper mapper,
            @Qualifier("aiServiceRestClient") RestClient aiServiceRestClient) {
        this.mapper = mapper;
        this.aiServiceRestClient = aiServiceRestClient;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(@JsonProperty("match_score") Integer matchScore,
            @JsonProperty("matched_skills") List<String> matchedSkills,
            @JsonProperty("missing_skills") List<String> missingSkills,
            @JsonProperty("relevant_experience") String relevantExperience,
            @JsonProperty("other_gaps") List<String> otherGaps) {
        public void validate() {
            if (matchScore == null || matchScore < 0 || matchScore > 100 || relevantExperience == null
                    || matchedSkills == null || missingSkills == null || otherGaps == null
                    || matchedSkills.stream().anyMatch(s -> s == null)
                    || missingSkills.stream().anyMatch(s -> s == null)
                    || otherGaps.stream().anyMatch(s -> s == null)) {
                throw new IllegalArgumentException("AI returned an invalid comparison result");
            }
        }
    }

    public Result compare(JobComparisonQueue.Task task) {
        try {
            String body = aiServiceRestClient.post()
                    .uri("/job-comparison")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("correlation_id", task.requestId().toString(),
                            "job_id", task.jobId(), "s3_key", task.s3Key(),
                            "job_description", task.description()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        int status = response.getStatusCode().value();
                        boolean retryable = status == 408 || status == 429 || status == 502
                                || status == 503 || status == 504;
                        String code = status == 401 || status == 403 ? "AI_AUTHENTICATION_FAILED"
                                : status == 422 ? "INVALID_COMPARISON_REQUEST"
                                : status == 429 ? "UPSTREAM_RATE_LIMITED"
                                : retryable ? "AI_UNAVAILABLE" : "AI_REQUEST_REJECTED";
                        try {
                            var envelope = mapper.readTree(response.getBody().readNBytes(8192));
                            if (task.requestId().toString().equals(envelope.path("correlation_id").asText())
                                    && ERROR_CODES.contains(envelope.path("error_code").asText())) {
                                code = envelope.path("error_code").asText();
                                retryable = retryable && envelope.path("retryable").asBoolean(false);
                            }
                        } catch (Exception ignored) {
                            // An untrusted body is neither logged nor shown to users.
                        }
                        throw new ComparisonFailure(code, retryable, retryAfter(response.getHeaders().getFirst("Retry-After")));
                    })
                    .body(String.class);
            var json = mapper.readTree(body == null ? "" : body);
            if (json == null || !task.requestId().toString().equals(json.path("correlation_id").asText()))
                throw new ComparisonFailure("INVALID_AI_RESPONSE", false);
            Result result = mapper.readerFor(Result.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .readValue(body);
            result.validate();
            return result;
        } catch (ComparisonFailure error) {
            throw error;
        } catch (org.springframework.web.client.ResourceAccessException error) {
            throw new ComparisonFailure("AI_UNAVAILABLE", true);
        } catch (Exception error) {
            throw new ComparisonFailure("INVALID_AI_RESPONSE", false);
        }
    }

    private static long retryAfter(String value) {
        if (value == null) return 0;
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            try {
                return Math.max(0, java.time.Duration.between(java.time.Instant.now(),
                        java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant()).getSeconds());
            } catch (java.time.DateTimeException invalid) {
                return 0;
            }
        }
    }

    private static final java.util.Set<String> ERROR_CODES = java.util.Set.of(
            "AI_AUTHENTICATION_FAILED", "INVALID_COMPARISON_REQUEST", "RESUME_UNREADABLE",
            "RESUME_UNAVAILABLE", "STORAGE_CONFIGURATION_ERROR", "AI_CONFIGURATION_ERROR",
            "UPSTREAM_RATE_LIMITED", "AI_UNAVAILABLE", "AI_TIMED_OUT", "INVALID_AI_RESPONSE",
            "INTERNAL_ERROR");
}
