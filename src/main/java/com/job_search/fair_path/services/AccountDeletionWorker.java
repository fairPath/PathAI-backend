package com.job_search.fair_path.services;

import java.time.Clock;
import java.time.Duration;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class AccountDeletionWorker {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionWorker.class);
    private final AccountDeletionQueue queue;
    private final S3Client s3;
    private final EmailService mail;
    private final Clock clock;
    private final Duration lease;
    private final Duration retryDelay;
    private final Duration operationTimeout;

    public AccountDeletionWorker(AccountDeletionQueue queue, S3Client s3, EmailService mail, Clock clock,
            @Value("${fairpath.account-deletion.lease}") Duration lease,
            @Value("${fairpath.account-deletion.retry-delay}") Duration retryDelay,
            @Value("${fairpath.account-deletion.operation-timeout}") Duration operationTimeout) {
        this.queue = queue;
        this.s3 = s3;
        this.mail = mail;
        this.clock = clock;
        this.lease = lease;
        this.retryDelay = retryDelay;
        this.operationTimeout = operationTimeout;
        if (operationTimeout.isNegative() || operationTimeout.isZero() || lease.compareTo(operationTimeout) <= 0) {
            throw new IllegalArgumentException("Account deletion lease must exceed the positive S3 operation timeout");
        }
    }

    public void run() {
        var now = clock.instant();
        queue.claim(now, now.plus(lease)).ifPresent(this::process);
    }

    private void process(AccountDeletionQueue.Task task) {
        String failureCode;
        try {
            queue.beginNotification(task, clock.instant()).ifPresent(this::sendNotification);
            failureCode = deleteResumeObjects(task);
        } catch (S3Exception failure) {
            var details = failure.awsErrorDetails();
            retry(task, safeS3Code(details == null ? null : details.errorCode()));
            return;
        } catch (RuntimeException failure) {
            retry(task, "CLEANUP_FAILED");
            return;
        }

        if (failureCode != null) {
            retry(task, failureCode);
            return;
        }
        try {
            queue.finishCleanup(task, clock.instant());
        } catch (RuntimeException failure) {
            retry(task, "DATABASE_CLEANUP_FAILED");
        }
    }

    private String deleteResumeObjects(AccountDeletionQueue.Task task) {
        String prefix = "resumes/" + task.owner() + "/";
        String nextToken = null;
        do {
            if (!renew(task)) return null;
            String continuationToken = nextToken;
            var page = s3.listObjectsV2(request -> request.bucket(task.bucket()).prefix(prefix)
                    .continuationToken(continuationToken)
                    .overrideConfiguration(config -> config.apiCallTimeout(operationTimeout)));
            var objects = page.contents().stream()
                    .map(object -> ObjectIdentifier.builder().key(object.key()).build()).toList();
            if (!objects.isEmpty()) {
                if (!renew(task)) return null;
                var deleted = s3.deleteObjects(request -> request.bucket(task.bucket())
                        .delete(batch -> batch.objects(objects).quiet(true))
                        .overrideConfiguration(config -> config.apiCallTimeout(operationTimeout)));
                for (var error : deleted.errors()) {
                    if (!"NoSuchKey".equals(error.code())) return safeS3Code(error.code());
                }
            }
            nextToken = page.nextContinuationToken();
        } while (nextToken != null);
        return null;
    }

    private boolean renew(AccountDeletionQueue.Task task) {
        var now = clock.instant();
        return queue.renew(task, now, now.plus(lease));
    }

    private void sendNotification(String email) {
        try {
            mail.sendDeletionStarted(email);
        } catch (MessagingException | RuntimeException failure) {
            log.warn("Account deletion start email failed");
        }
    }

    private void retry(AccountDeletionQueue.Task task, String code) {
        log.error("Account cleanup failed and will retry ({})", code);
        queue.retry(task, clock.instant().plus(retryDelay), code);
    }

    private String safeS3Code(String code) {
        if (code == null) return "S3_CLEANUP_FAILED";
        return switch (code) {
            case "AccessDenied", "ExpiredToken", "InternalError", "InvalidAccessKeyId", "InvalidArgument",
                    "NoSuchBucket", "RequestTimeout", "ServiceUnavailable", "SlowDown" -> code;
            default -> "S3_CLEANUP_FAILED";
        };
    }
}
