package com.job_search.fair_path.controllers;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import com.job_search.fair_path.dataTransferObject.ResumeConfirmRequestDTO;
import com.job_search.fair_path.dataTransferObject.ResumePresignUrlRequestDTO;
import com.job_search.fair_path.dataTransferObject.ResumePresignUrlResponseDTO;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.services.ResumeService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/resumes")
@Controller
public class ResumeController {
    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping("/presign-url")
    public ResponseEntity<ResumePresignUrlResponseDTO> createPresignUrl(@RequestBody ResumePresignUrlRequestDTO request,
            Authentication authentication) {
        {
            try {
                String filename = request.getFilename();
                User user = (User) authentication.getPrincipal();
                UUID userId = user.getId();
                ResumePresignUrlResponseDTO presignedUploadDTO = resumeService.createPresignUploadUrl(userId, filename);
                return ResponseEntity.ok(presignedUploadDTO);
            } catch (Exception e) {
                return ResponseEntity.badRequest().build();
            }
        }

    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirmUpload(@RequestBody ResumeConfirmRequestDTO request, Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            UUID userId = user.getId();
            resumeService.confirmUpload(request.getResumeId(), userId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteResume(Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        UUID userId = user.getId();
        resumeService.deleteResume(userId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/download")
    public ResponseEntity<?> downloadResume(Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        UUID userId = user.getId();
        String presignedDownloadUrl = resumeService.getPresignedDownloadUrl(userId);
        return ResponseEntity.ok(presignedDownloadUrl);
    }

}
