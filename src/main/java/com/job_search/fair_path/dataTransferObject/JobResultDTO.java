package com.job_search.fair_path.dataTransferObject;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class JobResultDTO {
    private String jobId;
    private String title;
    private String companyName;
    private LocalDateTime dateCreated;
    private String location;
    private String redirectUrl;
    private String jobDescription;
    private Double salaryMin;
    private Double salaryMax;
    private Boolean saved;
    private UUID resumeId;
    private Map<String, Object> resumeSnapshot;
    private BigDecimal matchScore;
    private List<String> matchedSkills;
    private List<String> missingSkills;
    private String relevantExperience;
    private List<String> otherGaps;
    private String comparisonStatus;
    private UUID comparisonRequestId;

    public JobResultDTO(String jobId, String title, String companyName, LocalDateTime dateCreated, String location,
            String redirectUrl,
            String jobDescription, Double salaryMin, Double salaryMax, Boolean saved) {
        this.jobId = jobId;
        this.title = title;
        this.companyName = companyName;
        this.dateCreated = dateCreated;
        this.location = location;
        this.redirectUrl = redirectUrl;
        this.jobDescription = jobDescription;
        this.salaryMin = salaryMin;
        this.salaryMax = salaryMax;
        this.saved = saved;
    }

    @Override
    public String toString() {
        return "{" +
                "jobId=" + jobId + "title='" + title + '\'' +
                ", companyName=" + companyName + ", dateCreated=" + dateCreated + ", location=" + location
                + ", redirectUrl=" + redirectUrl + ", salaryMin="
                + salaryMin + ", salaryMax=" + salaryMax +
                '}';
    }

}
