package com.job_search.fair_path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import static org.mockito.ArgumentMatchers.any;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.job_search.fair_path.dataTransferObject.RegisterUserDTO;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.UserRepository;
import com.job_search.fair_path.services.AccountDeletionQueue;
import com.job_search.fair_path.services.AccountDeletionService;
import com.job_search.fair_path.services.AccountDeletionWorker;
import com.job_search.fair_path.services.AuthenticationService;
import com.job_search.fair_path.services.EmailService;
import com.job_search.fair_path.services.JwtService;
import com.job_search.fair_path.services.ResumeService;
import com.job_search.fair_path.support.IntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDeletionTest extends IntegrationTestBase {
    private static final String EMAIL = "deletion@example.invalid";
    private static final String PASSWORD = "synthetic-password";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @Autowired AccountDeletionService deletion;
    @Autowired AccountDeletionWorker worker;
    @Autowired AccountDeletionQueue queue;
    @Autowired ResumeService resumeService;
    @Autowired AuthenticationService auth;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailService mail;

    private User owner;

    @BeforeEach
    void createAccount() {
        owner = new User("Test", "Owner", "test-owner", EMAIL, passwords.encode(PASSWORD));
        owner.setEnabled(true);
        owner = users.saveAndFlush(owner);
    }

    @Test
    void locksSessionsDeletesUsageAndSendsConfirmationBeforeReleasingSignup() throws Exception {
        mvc.perform(post("/account/deletion").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
        assertThat(users.findById(owner.getId()).orElseThrow().isEnabled()).isTrue();
        jdbc.update("INSERT INTO usage_record (id, user_id, kind, occurred_at) VALUES (gen_random_uuid(), ?, ?, ?)",
                owner.getId(), "ANALYSIS_COMPLETED", Timestamp.from(clock.instant()));

        String token = requestDeletion();
        assertThat(deletion.status(token)).isEqualTo("requested");
        mvc.perform(post("/account/deletion").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"synthetic-password\"}"))
                .andExpect(status().isUnauthorized());

        worker.run();
        assertThat(deletion.status(token)).isEqualTo("completed");
        assertThat(users.findById(owner.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_record WHERE user_id = ?", Long.class,
                owner.getId())).isZero();
        var signup = new RegisterUserDTO();
        signup.setEmail(EMAIL);
        assertThatThrownBy(() -> auth.signUp(signup))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        worker.run();
        verify(mail).sendDeletionConfirmation(EMAIL);
        assertThat(deletion.status(token)).isEqualTo("missing");
        assertThat(deletion.reservesEmail(EMAIL)).isFalse();
    }

    @Test
    void waitsForUploadAndRecoversExpiredLeaseThenDropsExhaustedMailJob() throws Exception {
        owner.setResumeUploadExpiresAt(clock.instant().plus(Duration.ofMinutes(15)));
        owner = users.saveAndFlush(owner);
        String token = requestDeletion();

        worker.run();
        assertThat(deletion.status(token)).isEqualTo("requested");
        clock.advance(Duration.ofMinutes(17));
        var now = clock.instant();
        assertThat(queue.claim(now, now.plusSeconds(30))).isPresent();
        worker.run();
        assertThat(deletion.status(token)).isEqualTo("requested");

        clock.advance(Duration.ofSeconds(31));
        worker.run();
        assertThat(deletion.status(token)).isEqualTo("completed");
        doThrow(new jakarta.mail.MessagingException("temporary synthetic failure"))
                .when(mail).sendDeletionConfirmation(EMAIL);
        worker.run();
        assertThat(deletion.reservesEmail(EMAIL)).isTrue();
        clock.advance(Duration.ofMinutes(15));
        worker.run();
        assertThat(deletion.status(token)).isEqualTo("missing");
        assertThat(deletion.reservesEmail(EMAIL)).isFalse();
    }

    @Test
    void uploadLinkSignsTheConditionThatPreventsWritesAfterDeletion() throws Exception {
        org.mockito.Mockito.when(s3Client.putObject(
                org.mockito.ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"synthetic-etag\"").build());
        try (var localSigner = S3Presigner.builder().region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            org.mockito.Mockito.when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                    .thenAnswer(call -> localSigner.presignPutObject(call.getArgument(0, PutObjectPresignRequest.class)));
            mvc.perform(post("/resumes/presign-url").header("Authorization", bearer())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"filename\":\"synthetic.pdf\"}"))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.etag").value("\"synthetic-etag\""));
        }
        assertThat(users.findById(owner.getId()).orElseThrow().getResumeUploadExpiresAt()).isAfter(clock.instant());
    }

    @Test
    void recreatingDeletedUploadPlaceholderChangesItsEtagAndCannotBeConfirmed() throws Exception {
        var head = new AtomicReference<HeadObjectResponse>();
        when(s3Client.headObject(org.mockito.ArgumentMatchers.<Consumer<HeadObjectRequest.Builder>>any()))
                .thenAnswer(call -> head.get());
        when(s3Client.putObject(
                org.mockito.ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class)))
                .thenAnswer(call -> {
                    var builder = PutObjectRequest.builder();
                    call.<Consumer<PutObjectRequest.Builder>>getArgument(0).accept(builder);
                    var request = builder.build();
                    var body = call.getArgument(1, RequestBody.class);
                    byte[] bytes;
                    try (var stream = body.contentStreamProvider().newStream()) {
                        bytes = stream.readAllBytes();
                    }
                    head.set(HeadObjectResponse.builder().contentLength((long) bytes.length)
                            .metadata(request.metadata()).build());
                    assertThat(request.ifNoneMatch()).isEqualTo("*");
                    return PutObjectResponse.builder().eTag("\"" + UUID.nameUUIDFromBytes(bytes) + "\"").build();
                });
        try (var localSigner = S3Presigner.builder().region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            org.mockito.Mockito.when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                    .thenAnswer(call -> localSigner.presignPutObject(call.getArgument(0, PutObjectPresignRequest.class)));
            var first = presignResume();
            mvc.perform(delete("/resumes/delete")
                    .header("Authorization", bearer())).andExpect(status().isOk());
            var second = presignResume();
            assertThat(second.get("etag")).isNotEqualTo(first.get("etag"));
            assertThat(second.get("resumeId")).isNotEqualTo(first.get("resumeId"));
            assertThatThrownBy(() -> resumeService.confirmUpload(
                    UUID.fromString(second.get("resumeId").asText()), owner.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private JsonNode presignResume() throws Exception {
        var result = mvc.perform(post("/resumes/presign-url").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"filename\":\"synthetic.pdf\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String requestDeletion() throws Exception {
        var result = mvc.perform(post("/account/deletion").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"synthetic-password\"}"))
                .andExpect(status().isAccepted()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String bearer() {
        return "Bearer " + jwt.generateToken(owner);
    }
}
