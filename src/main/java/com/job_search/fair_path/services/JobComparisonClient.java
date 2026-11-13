package com.job_search.fair_path.services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class JobComparisonClient {
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public JobComparisonClient(ObjectMapper mapper,
            @Value("${fairpath.ai-service.url}") String baseUrl,
            @Value("${fairpath.ai-service.internal-token}") String token) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !token.isBlank();
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

    public Result compare(JobComparisonQueue.Task task) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/job-comparison"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                        "job_id", task.jobId(), "s3_key", task.s3Key(), "job_description", task.description()))))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // The response body may contain private data; never include it in worker logs.
            throw new IllegalStateException("AI service returned HTTP " + response.statusCode());
        }
        Result result = mapper.readerFor(Result.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .readValue(response.body());
        if (result == null) throw new IllegalArgumentException("AI returned an empty comparison result");
        result.validate();
        return result;
    }
}
