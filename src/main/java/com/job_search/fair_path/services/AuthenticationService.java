package com.job_search.fair_path.services;

import com.job_search.fair_path.dataTransferObject.AuthUserDTO;
import com.job_search.fair_path.dataTransferObject.RegisterUserDTO;
import com.job_search.fair_path.dataTransferObject.VerifyUserDTO;
import com.job_search.fair_path.dataTransferObject.ForgotPasswordDTO;
import com.job_search.fair_path.dataTransferObject.UpdatePasswordDTO;
import com.job_search.fair_path.entity.User;
import com.job_search.fair_path.repository.UserRepository;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.Random;

@Service
public class AuthenticationService {
    private static final int RESET_TOKEN_VALIDITY_MINUTES = 15;
    private static final int VERIFY_CODE_VALIDITY_MINUTES = 15;

    @Value("${forgot.password.url}")
    private String forgotPasswordUrl;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;

    private final AccountDeletionService accountDeletion;

    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            EmailService emailService, AccountDeletionService accountDeletion) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.emailService = emailService;
        this.accountDeletion = accountDeletion;
    }

    public void signUp(RegisterUserDTO input) {
        User user = new User(input.getFirstName(), input.getLastName(), input.getUsername(), input.getEmail(),
                passwordEncoder.encode(input.getPassword()));
        user.setVerificationCode(generateVerificationCode());
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(VERIFY_CODE_VALIDITY_MINUTES));
        user.setEnabled(false);

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateKeyException("Email already exists");
        }
        sendVerificationEmail(user);

    }

    // Login Auth logic
    public User authenticate(AuthUserDTO input) {

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        input.getEmail(),
                        input.getPassword()));
        User user = userRepository.findByEmail(input.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.isEnabled()) {
            // thrown if they try to login and they are not verified
            throw new RuntimeException("Account not verified. Please verify your account.");
        }

        return user;
    }

    @Transactional
    public void verifyUser(VerifyUserDTO input) {
        Optional<User> userOptional = userRepository.findByEmailForUpdate(input.getEmail())
                .filter(user -> !accountDeletion.isPending(user.getId()));
        // checking to see if user is present in our User Table
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            if (user.getVerificationCodeExpiresAt().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("Verification code has expired");
            }
            // if the verification code user input is valid then we enable them
            if (user.getVerificationCode().equals(input.getVerificationCode())) {
                user.setEnabled(true);
                user.setVerificationCode(null);
                user.setVerificationCodeExpiresAt(null);
                // saving user to our repo
                userRepository.save(user);
            } else {
                // if incorrect verification code is entered
                throw new RuntimeException("Invalid verification code");
            }
        } else {
            // if user not present throw error
            throw new RuntimeException("User not found");
        }
    }

    @Transactional
    public void resendVerificationCode(AuthUserDTO input) {
        Optional<User> optionalUser = userRepository.findByEmailForUpdate(input.getEmail())
                .filter(user -> !accountDeletion.isPending(user.getId()));
        if (optionalUser.isPresent()) {
            User user = optionalUser.get();
            if (user.isEnabled()) {
                throw new RuntimeException("Account is already verified.");
            }
            user.setVerificationCode(generateVerificationCode());
            user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(VERIFY_CODE_VALIDITY_MINUTES));
            sendVerificationEmail(user);
            userRepository.save(user);
        } else {
            throw new RuntimeException("User not found");
        }
    }

    public void sendVerificationEmail(User user) {
        String subject = "Account Verification";
        String verificationCode = user.getVerificationCode();
        // Message itself will be in html format
        String htmlMessage = "<html>"
                + "<body style=\"font-family: Arial, sans-serif;\">"
                + "<div style=\"background-color: #f5f5f5; padding: 20px;\">"
                + "<h2 style=\"color: #333;\">Welcome to our app!</h2>"
                + "<p style=\"font-size: 16px;\">Please enter the verification code below to continue:</p>"
                + "<div style=\"background-color: #fff; padding: 20px; border-radius: 5px; box-shadow: 0 0 10px rgba(0,0,0,0.1);\">"
                + "<h3 style=\"color: #333;\">Verification Code:</h3>"
                + "<p style=\"font-size: 18px; font-weight: bold; color: #007bff;\">" + verificationCode + "</p>"
                + "</div>"
                + "</div>"
                + "</body>"
                + "</html>";
        try {
            emailService.sendEmail(user.getEmail(), subject, htmlMessage);
        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }

    private String generateVerificationCode() {
        Random random = new Random();
        int code = random.nextInt(900000) + 100000;
        return String.valueOf(code);
    }

    private String generateResetToken() {
        byte[] bytes = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateResetHash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("Error generating reset hash", e);
        }
    }

    @Transactional
    public void sendForgotPasswordEmail(ForgotPasswordDTO input) {
        Optional<User> optionalUser = userRepository.findByEmailForUpdate(input.getEmail())
                .filter(user -> !accountDeletion.isPending(user.getId()));
        // need to generate token or unique code for resetting password
        if (optionalUser.isPresent()) {
            User user = optionalUser.get();
            try {

                String resetToken = generateResetToken();
                user.setResetTokenHash(generateResetHash(resetToken));
                user.setResetTokenExpiresAt(LocalDateTime.now().plusMinutes(RESET_TOKEN_VALIDITY_MINUTES));
                String newForgotPasswordUrl = forgotPasswordUrl + "?token=" + resetToken;
                String subject = "Reset Password";
                String htmlMessage = "<html>"
                        + "<body style=\"font-family: Arial, sans-serif;\">"
                        + "<div style=\"background-color: #f5f5f5; padding: 20px;\">"
                        + "<h2 style=\"color: #333;\">Welcome to our app!</h2>"
                        + "<p style=\"font-size: 16px;\">Please click on the link below to update your password:</p>"
                        + "<div style=\"background-color: #fff; padding: 20px; border-radius: 5px; box-shadow: 0 0 10px rgba(0,0,0,0.1);\">"
                        + "<h3 style=\"color: #333;\">Forgot Password:</h3>"
                        + "<a style=\"font-size: 18px; font-weight: bold; color: #007bff;\" href='"
                        + newForgotPasswordUrl
                        + "'>Click here to reset password" + "</a>"
                        + "</div>"
                        + "</div>"
                        + "</body>"
                        + "</html>";

                userRepository.save(user);
                emailService.sendEmail(input.getEmail(), subject, htmlMessage);
            } catch (MessagingException e) {
                user.setResetTokenHash(null);
                user.setResetTokenExpiresAt(null);
                userRepository.save(user);
                throw new RuntimeException("Failed to send reset password email", e);
            }
        }

    }

    @Transactional
    public void updatePassword(UpdatePasswordDTO input) {
        String resetHash = generateResetHash(input.getResetToken());
        Optional<User> userOptional = userRepository.findActiveByResetTokenHashForUpdate(resetHash);
        if (userOptional.isPresent()) {

            User user = userOptional.get();
            user.setPassword(passwordEncoder.encode(input.getPassword()));
            user.setResetTokenHash(null);
            user.setResetTokenExpiresAt(null);
            userRepository.save(user);
        }
    }

}
