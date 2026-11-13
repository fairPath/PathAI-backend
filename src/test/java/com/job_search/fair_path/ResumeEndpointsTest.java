package com.job_search.fair_path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import com.job_search.fair_path.entity.Resume;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.ResumeRepository;
import com.job_search.fair_path.repository.UserRepository;
import com.job_search.fair_path.services.JwtService;
import com.job_search.fair_path.support.IntegrationTestBase;

import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Proves the harness end to end on existing behaviour: real security filter
 * (a real JWT for a real User row), real Postgres, faked S3.
 */
class ResumeEndpointsTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private JwtService jwtService;

    private User user;
    private String bearerToken;

    @BeforeEach
    void createUserAndToken() {
        String unique = UUID.randomUUID().toString();
        User newUser = new User("Test", "User", "user-" + unique, unique + "@example.invalid", "not-a-real-hash");
        newUser.setEnabled(true);
        user = userRepository.save(newUser);
        bearerToken = "Bearer " + jwtService.generateToken(user);
    }

    @Test
    void confirmActivatesAPendingResumeAndDeleteRemovesIt() throws Exception {
        Resume pending = new Resume();
        pending.setUserId(user.getId());
        pending.setS3Bucket("test-bucket");
        pending.setS3Key("resumes/" + user.getId() + "/active.pdf");
        pending.setFileName("synthetic-resume.pdf");
        pending.setStatus(Resume.Status.PENDING);
        pending = resumeRepository.save(pending);

        // confirm checks the uploaded object exists and is non-empty
        when(s3Client.headObject(anyHeadObjectConsumer()))
                .thenReturn(HeadObjectResponse.builder().contentLength(1234L).build());

        mockMvc.perform(post("/resumes/confirm")
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeId\":\"" + pending.getId() + "\"}"))
                .andExpect(status().isOk());

        assertThat(resumeRepository.findById(pending.getId()))
                .get()
                .extracting(Resume::getStatus)
                .isEqualTo(Resume.Status.ACTIVE);

        mockMvc.perform(delete("/resumes/delete")
                .header("Authorization", bearerToken))
                .andExpect(status().isOk());

        assertThat(resumeRepository.findByUserId(user.getId())).isEmpty();
        // the stored PDF must be removed from S3 too, not just the database row
        verify(s3Client).deleteObject(anyDeleteObjectConsumer());
    }

    @Test
    void confirmingAnUploadMovesTheResumeUpdatedAtForward() throws Exception {
        Resume pending = new Resume();
        pending.setUserId(user.getId());
        pending.setS3Bucket("test-bucket");
        pending.setS3Key("resumes/" + user.getId() + "/active.pdf");
        pending.setFileName("synthetic-resume.pdf");
        pending.setStatus(Resume.Status.PENDING);
        pending = resumeRepository.save(pending);
        Instant before = resumeUpdatedAt();

        when(s3Client.headObject(anyHeadObjectConsumer()))
                .thenReturn(HeadObjectResponse.builder().contentLength(1234L).build());
        mockMvc.perform(post("/resumes/confirm")
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resumeId\":\"" + pending.getId() + "\"}"))
                .andExpect(status().isOk());

        // A re-upload reuses the row, so updatedAt is what tells a replaced file apart.
        assertThat(resumeUpdatedAt()).isAfter(before);
    }

    private Instant resumeUpdatedAt() throws Exception {
        String body = mockMvc.perform(get("/users/me").header("Authorization", bearerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Instant.parse(JsonPath.read(body, "$.resumeUpdatedAt"));
    }

    // Disambiguate the (Consumer) overloads from the (Request) overloads.
    private static Consumer<HeadObjectRequest.Builder> anyHeadObjectConsumer() {
        return any();
    }

    private static Consumer<DeleteObjectRequest.Builder> anyDeleteObjectConsumer() {
        return any();
    }
}
