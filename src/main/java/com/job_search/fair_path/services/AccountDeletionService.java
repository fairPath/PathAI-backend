package com.job_search.fair_path.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.ResumeRepository;
import com.job_search.fair_path.repository.UserRepository;

@Service
public class AccountDeletionService {
    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;
    private final ResumeRepository resumes;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String bucket;
    private final Duration uploadDrain;

    public AccountDeletionService(UserRepository users, ResumeRepository resumes, PasswordEncoder passwords,
            JdbcTemplate jdbc, Clock clock, @Value("${aws.s3.bucket}") String bucket,
            @Value("${fairpath.account-deletion.upload-drain:1m}") Duration uploadDrain) {
        this.users = users;
        this.resumes = resumes;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.clock = clock;
        this.bucket = bucket;
        this.uploadDrain = uploadDrain;
    }

    @Transactional
    public String request(UUID owner, String password) {
        User user = users.findByIdForUpdate(owner)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (password == null || !passwords.matches(password, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect password");
        }

        var resume = resumes.findByUserId(owner);
        Instant now = clock.instant();
        Instant expires = user.getResumeUploadExpiresAt();
        Instant due = expires == null || !expires.plus(uploadDrain).isAfter(now)
                ? now : expires.plus(uploadDrain);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        jdbc.update("""
                INSERT INTO account_deletion (token_hash, owner_id, bucket, object_key, due_at)
                VALUES (?, ?, ?, ?, ?)
                """, hash(token), owner, resume.map(r -> r.getS3Bucket()).orElse(bucket),
                resume.map(r -> r.getS3Key()).orElse("resumes/" + owner + "/active.pdf"), Timestamp.from(due));
        user.setEnabled(false);
        user.setVerificationCode(null);
        user.setVerificationCodeExpiresAt(null);
        user.setResetTokenHash(null);
        user.setResetTokenExpiresAt(null);
        return token;
    }

    public String status(String token) {
        if (token == null || token.length() != 43) return "missing";
        return jdbc.query("SELECT owner_id IS NULL FROM account_deletion WHERE token_hash = ?",
                (rs, row) -> rs.getBoolean(1) ? "completed" : "requested", hash(token))
                .stream().findFirst().orElse("missing");
    }

    public boolean isPending(UUID owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM account_deletion WHERE owner_id = ?)", Boolean.class, owner));
    }

    public boolean reservesEmail(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (SELECT 1 FROM users WHERE email = ?)
                    OR EXISTS (SELECT 1 FROM account_deletion WHERE email = ?)
                """, Boolean.class, email, email));
    }

    private static String hash(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
