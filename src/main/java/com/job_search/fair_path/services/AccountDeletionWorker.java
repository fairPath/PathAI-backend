package com.job_search.fair_path.services;

import java.time.Clock;
import java.time.Duration;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

@Component
public class AccountDeletionWorker {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionWorker.class);
    private final AccountDeletionQueue queue;
    private final S3Client s3;
    private final EmailService mail;
    private final Clock clock;
    private final Duration emailWindow;
    private final Duration retryDelay;
    private final Duration operationTimeout;

    public AccountDeletionWorker(AccountDeletionQueue queue, S3Client s3, EmailService mail, Clock clock,
            @Value("${fairpath.account-deletion.email-window:15m}") Duration emailWindow,
            @Value("${fairpath.account-deletion.retry-delay:30s}") Duration retryDelay,
            @Value("${fairpath.account-deletion.operation-timeout:20s}") Duration operationTimeout) {
        this.queue = queue;
        this.s3 = s3;
        this.mail = mail;
        this.clock = clock;
        this.emailWindow = emailWindow;
        this.retryDelay = retryDelay;
        this.operationTimeout = operationTimeout;
    }

    public void run() {
        var now = clock.instant();
        queue.claim(now, now.plus(operationTimeout.multipliedBy(3))).ifPresent(this::process);
    }

    private void process(AccountDeletionQueue.Task task) {
        if (task.owner() == null) {
            sendConfirmation(task);
            return;
        }
        try {
            s3.deleteObject(request -> request.bucket(task.bucket()).key(task.key())
                    .overrideConfiguration(config -> config.apiCallTimeout(operationTimeout)));
            var now = clock.instant();
            queue.finishCleanup(task, now, now.plus(emailWindow));
        } catch (RuntimeException failure) {
            log.warn("Account cleanup failed and will retry ({})", failure.getClass().getSimpleName());
            queue.retry(task, clock.instant().plus(retryDelay));
        }
    }

    private void sendConfirmation(AccountDeletionQueue.Task task) {
        if (!clock.instant().isBefore(task.emailDeadline())) {
            queue.forget(task);
            return;
        }
        try {
            mail.sendDeletionConfirmation(task.email());
        } catch (MessagingException | MailException failure) {
            if (permanentMailFailure(failure)) {
                queue.forget(task);
                return;
            }
            var next = clock.instant().plus(retryDelay);
            queue.retry(task, next.isBefore(task.emailDeadline()) ? next : task.emailDeadline());
            return;
        }
        queue.forget(task);
    }

    private boolean permanentMailFailure(Exception failure) {
        if (failure instanceof org.springframework.mail.MailSendException sendFailure) {
            for (Exception messageFailure : sendFailure.getMessageExceptions()) {
                if (permanentMailFailure(messageFailure)) return true;
            }
        }
        if (failure instanceof org.eclipse.angus.mail.smtp.SMTPAddressFailedException smtp) {
            return smtp.getReturnCode() >= 500 && smtp.getReturnCode() < 600;
        }
        if (failure instanceof org.eclipse.angus.mail.smtp.SMTPSendFailedException smtp) {
            return smtp.getReturnCode() >= 500 && smtp.getReturnCode() < 600;
        }
        if (failure instanceof MessagingException messaging && messaging.getNextException() != null) {
            return permanentMailFailure(messaging.getNextException());
        }
        return false;
    }
}
