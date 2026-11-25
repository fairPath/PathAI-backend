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
            @Value("${fairpath.account-deletion.upload-drain}") Duration uploadDrain) {
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
        User user = users.findActiveByIdForUpdate(owner)
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

        int disabled = jdbc.update("""
                UPDATE users SET enabled = false, verification_code = NULL, verification_expiration = NULL,
                    reset_token_hash = NULL, reset_expiration = NULL
                WHERE id = ? AND enabled = true
                """, owner);
        if (disabled == 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        jdbc.update("""
                INSERT INTO account_deletion (token_hash, owner_id, bucket, due_at)
                VALUES (?, ?, ?, ?)
                """, hash(token), owner, resume.map(r -> r.getS3Bucket()).orElse(bucket), Timestamp.from(due));
        return token;
    }

    public String status(String token) {
        if (token == null || token.length() != 43) return "missing";
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM account_deletion WHERE token_hash = ?)", Boolean.class, hash(token)))
                ? "requested" : "missing";
    }

    public boolean isPending(UUID owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM account_deletion WHERE owner_id = ?)", Boolean.class, owner));
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
