package com.job_search.fair_path.services;

import com.job_search.fair_path.exceptions.ComparisonFailure;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
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
            @JsonProperty("other_gaps") List<String> otherGaps,
            @JsonProperty("skill_match_score") Integer skillMatchScore,
            @JsonProperty("experience_match_score") Integer experienceMatchScore,
            @JsonProperty("resume_advice") String resumeAdvice) {
        public void validate() {
            if (matchScore == null || matchScore < 0 || matchScore > 100 || relevantExperience == null
                    || skillMatchScore == null || skillMatchScore < 0 || skillMatchScore > 100
                    || experienceMatchScore == null || experienceMatchScore < 0 || experienceMatchScore > 100
                    || resumeAdvice == null || resumeAdvice.isBlank()
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
        } catch (Exception error) {
            throw AiServiceErrors.comparisonFailure(error, task.requestId());
        }
    }

}
