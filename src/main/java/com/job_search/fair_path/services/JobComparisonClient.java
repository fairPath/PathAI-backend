package com.job_search.fair_path.services;

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

    public Result compare(JobComparisonQueue.Task task) throws Exception {
        String body = aiServiceRestClient.post()
                .uri("/job-comparison")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("job_id", task.jobId(), "s3_key", task.s3Key(), "job_description", task.description()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    // The response body may contain private data; never include it in worker logs.
                    throw new IllegalStateException("AI service returned HTTP " + response.getStatusCode().value());
                })
                .body(String.class);
        Result result = mapper.readerFor(Result.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .readValue(body);
        if (result == null) throw new IllegalArgumentException("AI returned an empty comparison result");
        result.validate();
        return result;
    }
}
