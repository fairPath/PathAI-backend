package com.job_search.fair_path.dataTransferObject;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ResumePresignUrlResponseDTO {

    private UUID resumeId;
    private String presignedUrl;
    private String etag;

}
