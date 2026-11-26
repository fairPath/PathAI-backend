package com.job_search.fair_path.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.util.Map;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

class EmailDeliveryTest {
    @Test
    void doesNotRetryWhenClosingSmtpFailsAfterSending() {
        var failure = new MailSendException("Failed to close server connection after message sending",
                new MessagingException("Connection closed"));

        assertThat(outcome(failure)).isEqualTo(EmailDelivery.Outcome.TERMINAL_FAILURE);
    }

    @Test
    void doesNotRetryWhenSmtpTimesOutWithoutConfirmingRejection() {
        var failure = new MessagingException("SMTP response timed out", new SocketTimeoutException());

        assertThat(outcome(mailFailure(failure))).isEqualTo(EmailDelivery.Outcome.TERMINAL_FAILURE);
    }

    @Test
    void retriesExplicitTemporaryRejectionsButNotPermanentRejections() {
        var temporary = new SMTPSendFailedException("DATA", 451, "Temporary failure", null, null, null, null);
        var permanent = new SMTPSendFailedException("DATA", 550, "Permanent failure", null, null, null, null);

        assertThat(outcome(mailFailure(temporary))).isEqualTo(EmailDelivery.Outcome.RETRYABLE_FAILURE);
        assertThat(outcome(mailFailure(permanent))).isEqualTo(EmailDelivery.Outcome.TERMINAL_FAILURE);
    }

    @Test
    void retriesTemporaryAuthenticationRepliesButNotInvalidCredentials() {
        var temporary = new MailAuthenticationException(
                new AuthenticationFailedException("454 4.7.0 Temporary authentication failure"));
        var permanent = new MailAuthenticationException(
                new AuthenticationFailedException("535-5.7.8 Username and password not accepted"));

        assertThat(outcome(temporary)).isEqualTo(EmailDelivery.Outcome.RETRYABLE_FAILURE);
        assertThat(outcome(permanent)).isEqualTo(EmailDelivery.Outcome.TERMINAL_FAILURE);
    }

    private EmailDelivery.Outcome outcome(RuntimeException failure) {
        return EmailDelivery.attempt("Test", () -> { throw failure; });
    }

    private MailSendException mailFailure(Exception failure) {
        return new MailSendException(Map.of("test-email", failure));
    }
}
