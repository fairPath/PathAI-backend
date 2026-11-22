package com.job_search.fair_path.services;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    // Autowired used to inject instance of java mail sender into the mailsender
    // field
    @Autowired
    private JavaMailSender emailSender;

    @org.springframework.beans.factory.annotation.Value("${spring.mail.username}")
    private String sender;

    public void sendDeletionConfirmation(String to) throws MessagingException {
        MimeMessage message = emailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message);
        helper.setFrom(sender);
        helper.setTo(to);
        helper.setSubject("FairPath account deleted");
        helper.setText("Your FairPath account and its data have been deleted.");
        emailSender.send(message);
    }

    public void sendEmail(String to, String subject, String text) throws MessagingException {
        MimeMessage message = emailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);

        helper.setTo(to);
        // the boolean parameter as true indicate the message will be multi part which
        // will allow for attachment and html content
        // and thats helpful when designing verification email itself
        helper.setSubject(subject);
        // using html to get a nice email design if you want regualr email format set it
        // to false
        helper.setText(text, true);

        emailSender.send(message);
    }
}