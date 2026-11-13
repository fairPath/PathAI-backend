package com.job_search.fair_path.services;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.job_search.fair_path.dataTransferObject.JobResultDTO;

@Service
public class JobService {

    private final RestTemplate restTemplate;
    private final SavedJobService savedJobService;

    @Value("${APP_ID}")
    private String appId;

    @Value("${APP_KEY}")
    private String appKey;

    private final String adzunaApiUrl = "https://api.adzuna.com/v1/api/jobs/us/search/1";

    public JobService(RestTemplateBuilder builder, SavedJobService savedJobService) {
        this.restTemplate = builder.build();
        this.savedJobService = savedJobService;
    }

    public List<JobResultDTO> getJobs(String where, String titleOnly, Integer salaryMin, String company,
            String fullTime,
            String partTime,
            String contract, Authentication authentication) {
        String url = adzunaApiUrl + "?app_id=" + appId + "&app_key=" + appKey;
        if (where != null && !where.isEmpty())
            url += "&where=" + where;
        if (titleOnly != null && !titleOnly.isEmpty())
            url += "&title_only=" + titleOnly;
        if (salaryMin != null && salaryMin != 0)
            url += "&salary_min=" + salaryMin;
        if (company != null && !company.isEmpty())
            url += "&company=" + company;
        if (fullTime != null && !fullTime.isEmpty())
            url += "&full_time=" + fullTime;
        if (partTime != null && !partTime.isEmpty())
            url += "&part_time=" + partTime;
        if (contract != null && !contract.isEmpty())
            url += "&contract=" + contract;
        url += "&results_per_page=120";
        String apiResponse = restTemplate.getForObject(url, String.class);
        List<JobResultDTO> savedJobs = savedJobService.getSavedJobsForUser(authentication);
        List<JobResultDTO> jobs = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, JobResultDTO> savedJobsById = new HashMap<>();
        for (JobResultDTO job : savedJobs) {
            savedJobsById.put(job.getJobId(), job);
        }
        try {
            JsonNode rootNode = mapper.readTree(apiResponse);
            ArrayNode arrayNode = (ArrayNode) rootNode.get("results");

            if (arrayNode != null) {
                for (JsonNode node : arrayNode) {
                    String title = node.get("title").asText();
                    String companyName = node.path("company").path("display_name").asText();
                    String dateStr = node.get("created").asText();
                    LocalDateTime dateCreated = ZonedDateTime.parse(dateStr).toLocalDateTime();
                    String locationDisplayName = node.path("location").path("display_name").asText();
                    String locationCountry = node.get("location").get("area").get(0).asText();
                    String location = new StringBuilder(
                            locationDisplayName + ", " + locationCountry).toString();
                    String redirectUrl = node.get("redirect_url").asText();
                    String jobId = node.get("id").asText();
                    String jobDescription = node.get("description").asText();
                    Double jobSalaryMin = node.get("salary_min").asDouble();
                    Double jobSalaryMax = node.get("salary_max").asDouble();
                    Boolean saved = false;

                    JobResultDTO savedJob = savedJobsById.get(jobId);
                    if (savedJob != null) {
                        saved = true;
                    }

                    JobResultDTO job = new JobResultDTO(jobId, title, companyName, dateCreated, location, redirectUrl,
                            jobDescription, jobSalaryMin, jobSalaryMax, saved);
                    if (savedJob != null) {
                        job.setResumeId(savedJob.getResumeId());
                        job.setResumeSnapshot(savedJob.getResumeSnapshot());
                        job.setComparisonStatus(savedJob.getComparisonStatus());
                        job.setComparisonRequestId(savedJob.getComparisonRequestId());
                        job.setMatchScore(savedJob.getMatchScore());
                        job.setMatchedSkills(savedJob.getMatchedSkills());
                        job.setMissingSkills(savedJob.getMissingSkills());
                        job.setRelevantExperience(savedJob.getRelevantExperience());
                        job.setOtherGaps(savedJob.getOtherGaps());
                    }
                    jobs.add(job);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return jobs;
    }
}
