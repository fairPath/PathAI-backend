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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;
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
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.UserRepository;
import com.job_search.fair_path.services.AccountDeletionQueue;
import com.job_search.fair_path.services.AccountDeletionService;
import com.job_search.fair_path.services.AccountDeletionWorker;
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
    void locksSessionsAndDeletesEveryOwnedRowAndObject() throws Exception {
        mvc.perform(post("/account/deletion").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
        assertThat(users.findById(owner.getId()).orElseThrow().isEnabled()).isTrue();

        String prefix = "resumes/" + owner.getId() + "/";
        var keys = List.of(prefix + "active.pdf", prefix + "original.pdf",
                prefix + "saved-job-snapshots/snapshot.pdf");
        seedOwnedData(keys.get(0));
        when(s3Client.listObjectsV2(org.mockito.ArgumentMatchers.<Consumer<ListObjectsV2Request.Builder>>any()))
                .thenAnswer(call -> {
                    var builder = ListObjectsV2Request.builder();
                    call.<Consumer<ListObjectsV2Request.Builder>>getArgument(0).accept(builder);
                    var request = builder.build();
                    assertThat(request.prefix()).isEqualTo(prefix);
                    assertThat(request.bucket()).isEqualTo("test-bucket");
                    if (request.continuationToken() == null) {
                        return objectPage(keys.subList(0, 2), "next-page");
                    }
                    assertThat(request.continuationToken()).isEqualTo("next-page");
                    return objectPage(keys.subList(2, 3), null);
                });
        var removed = new ArrayList<String>();
        when(s3Client.deleteObjects(org.mockito.ArgumentMatchers.<Consumer<DeleteObjectsRequest.Builder>>any()))
                .thenAnswer(call -> {
                    var builder = DeleteObjectsRequest.builder();
                    call.<Consumer<DeleteObjectsRequest.Builder>>getArgument(0).accept(builder);
                    var request = builder.build();
                    assertThat(request.bucket()).isEqualTo("test-bucket");
                    removed.addAll(request.delete().objects().stream().map(ObjectIdentifier::key).toList());
                    return DeleteObjectsResponse.builder().build();
                });
        doThrow(new jakarta.mail.MessagingException("synthetic mail failure"))
                .when(mail).sendDeletionStarted(EMAIL);

        String token = requestDeletion();
        assertThat(deletion.status(token)).isEqualTo("requested");
        assertThat(users.findById(owner.getId()).orElseThrow().isEnabled()).isFalse();
        mvc.perform(post("/account/deletion").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"synthetic-password\"}"))
                .andExpect(status().isUnauthorized());

        worker.run();
        worker.run();
        verify(mail).sendDeletionStarted(EMAIL);
        assertThat(removed).containsExactlyElementsOf(keys);
        for (String table : List.of("analysis", "saved_jobs", "usage_record", "resume", "account_deletion")) {
            String ownerColumn = table.equals("account_deletion") ? "owner_id" : "user_id";
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + ownerColumn + " = ?",
                    Long.class, owner.getId())).as(table).isZero();
        }
        assertThat(users.findById(owner.getId())).isEmpty();
        assertThat(deletion.status(token)).isEqualTo("missing");
    }

    @Test
    void waitsForUploadAndRecoversExpiredLeaseThenRecordsS3FailureAndRetriesCleanup() throws Exception {
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

        String key = "resumes/" + owner.getId() + "/active.pdf";
        when(s3Client.listObjectsV2(org.mockito.ArgumentMatchers.<Consumer<ListObjectsV2Request.Builder>>any()))
                .thenReturn(objectPage(List.of(key), null));
        when(s3Client.deleteObjects(org.mockito.ArgumentMatchers.<Consumer<DeleteObjectsRequest.Builder>>any()))
                .thenReturn(DeleteObjectsResponse.builder().errors(
                        S3Error.builder().key(key).code("AccessDenied").message("synthetic private detail").build()).build(),
                        DeleteObjectsResponse.builder().build());

        clock.advance(Duration.ofSeconds(31));
        worker.run();
        assertThat(deletion.status(token)).isEqualTo("requested");
        assertThat(users.findById(owner.getId()).orElseThrow().isEnabled()).isFalse();
        var failure = jdbc.queryForMap("SELECT attempts, last_error FROM account_deletion WHERE owner_id = ?",
                owner.getId());
        assertThat(failure).containsEntry("attempts", 1).containsEntry("last_error", "AccessDenied");

        clock.advance(Duration.ofSeconds(31));
        worker.run();
        assertThat(deletion.status(token)).isEqualTo("missing");
        verify(mail).sendDeletionStarted(EMAIL);
    }

    private ListObjectsV2Response objectPage(List<String> keys, String nextToken) {
        return ListObjectsV2Response.builder().nextContinuationToken(nextToken)
                .contents(keys.stream().map(key -> S3Object.builder().key(key).build()).toList()).build();
    }

    private void seedOwnedData(String key) {
        UUID resumeId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO resume (id, user_id, s3_bucket, s3_key, file_name, status, created_at, updated_at)
                VALUES (?, ?, 'test-bucket', ?, 'synthetic.pdf', 'ACTIVE', ?, ?)
                """, resumeId, owner.getId(), key, now, now);
        jdbc.update("""
                INSERT INTO analysis (id, user_id, resume_id, status, created_at)
                VALUES (?, ?, ?, 'COMPLETED', ?)
                """, UUID.randomUUID(), owner.getId(), resumeId, now);
        jdbc.update("""
                INSERT INTO saved_jobs (id, user_id, job_id, resume_id, comparison_status)
                VALUES (?, ?, 'synthetic-job', ?, 'COMPLETED')
                """, UUID.randomUUID(), owner.getId(), resumeId);
        jdbc.update("INSERT INTO usage_record (id, user_id, kind, occurred_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), owner.getId(), "ANALYSIS_COMPLETED", now);
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
