package com.job_search.fair_path.controllers;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import com.job_search.fair_path.services.SavedJobService;
import com.job_search.fair_path.services.SavedJobLimitExceededException;
import com.job_search.fair_path.dataTransferObject.JobResultDTO;

@RestController
@RequestMapping("/saved-jobs")
public class SaveJobController {
    private static final Logger logger = LoggerFactory.getLogger(SaveJobController.class);

    private final SavedJobService savedJobService;

    public SaveJobController(SavedJobService savedJobService) {
        this.savedJobService = savedJobService;
    }

    @GetMapping("/list")
    public List<JobResultDTO> getSavedJobsForUser(Authentication authentication) {
        return savedJobService.getSavedJobsForUser(authentication);
    }

    @GetMapping("/detail")
    public ResponseEntity<JobResultDTO> getSavedJobForUser(Authentication authentication,
            @RequestParam(required = true) String jobId) {
        JobResultDTO savedJob = savedJobService.getSavedJobForUser(authentication, jobId);
        return savedJob == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(savedJob);
    }

    @PostMapping("/prepare")
    public ResponseEntity<JobResultDTO> prepareJobForUser(@RequestBody JobResultDTO jobRequestDTO,
            Authentication authentication) {
        try {
            return ResponseEntity.ok(savedJobService.prepareJob(jobRequestDTO, authentication));
        } catch (SavedJobLimitExceededException error) {
            return ResponseEntity.status(409).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        } catch (Exception error) {
            logger.error("Failed to prepare a saved job", error);
            return ResponseEntity.internalServerError().build();
        }

    }

    @GetMapping("/status")
    public ResponseEntity<List<JobResultDTO>> getComparisonStatuses(Authentication authentication,
            @RequestParam("jobId") List<String> jobIds) {
        try {
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(savedJobService.getComparisonStatuses(authentication, jobIds));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<String> deleteJobForUser(Authentication authentication,
            @RequestParam(required = true) String jobId) {
        try {
            savedJobService.deleteSavedJob(authentication, jobId);
            return ResponseEntity.ok("successully deleted");
        } catch (Exception error) {
            return ResponseEntity.internalServerError().body("failed to delete in table");
        }
    }

}
