package com.job_search.fair_path.services;

import java.util.regex.Pattern;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

final class EmailDelivery {
    private static final Logger log = LoggerFactory.getLogger(EmailDelivery.class);
    private static final Pattern TEMPORARY_SMTP_REPLY = Pattern.compile("^4[0-9]{2}(?:[ -]|$)");

    enum Outcome { SENT, RETRYABLE_FAILURE, TERMINAL_FAILURE }

    @FunctionalInterface
    interface Send {
        void run() throws MessagingException;
    }

    static Outcome attempt(String description, Send send) {
        try {
            send.run();
            return Outcome.SENT;
        } catch (MessagingException | RuntimeException failure) {
            log.warn("{} email failed ({})", description, failure.getClass().getSimpleName());
            return isRetryable(failure) ? Outcome.RETRYABLE_FAILURE : Outcome.TERMINAL_FAILURE;
        }
    }

    private static boolean isRetryable(Exception failure) {
        if (failure instanceof MailAuthenticationException authentication
                && authentication.getCause() instanceof AuthenticationFailedException reply) {
            // Angus exposes the AUTH reply as text rather than a numeric return code.
            String message = reply.getMessage();
            return message != null && TEMPORARY_SMTP_REPLY.matcher(message).find();
        }
        if (!(failure instanceof MailSendException sendFailure) || sendFailure.getFailedMessages().isEmpty()) {
            // An empty map can mean SMTP shutdown failed after the message was accepted.
            return false;
        }
        return sendFailure.getFailedMessages().values().stream()
                .allMatch(EmailDelivery::isTemporarySmtpFailure);
    }

    private static boolean isTemporarySmtpFailure(Exception failure) {
        if (failure instanceof SMTPAddressFailedException addressFailure) {
            return isTemporaryReply(addressFailure.getReturnCode());
        }
        if (failure instanceof SMTPSendFailedException sendFailure) {
            return isTemporaryReply(sendFailure.getReturnCode());
        }
        if (failure instanceof MessagingException messagingFailure && messagingFailure.getNextException() != null) {
            return isTemporarySmtpFailure(messagingFailure.getNextException());
        }
        return failure instanceof MailConnectException;
    }

    private static boolean isTemporaryReply(int code) {
        return code >= 400 && code < 500;
    }

    private EmailDelivery() {}
}
