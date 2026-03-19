package com.authx.authservice.service;

import com.authx.authservice.exception.EmailDeliveryException;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:${spring.mail.username:}}")
    private String fromAddress;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${app.frontend-url:}")
    private String frontendUrl;

    @PostConstruct
    void normalizeMailCredentials() {
        if (mailSender instanceof JavaMailSenderImpl sender) {
            if (username != null) {
                sender.setUsername(username.trim());
            }

            if (password != null) {
                sender.setPassword(password.replaceAll("\\s+", ""));
            }
        }
    }

    public void sendVerificationEmail(String email, String token, String originHeader, String refererHeader) {
        String verificationLink = buildLink("verify", token, originHeader, refererHeader);
        String subject = "Verify your AuthX account";
        String textBody = """
                Welcome to AuthX.

                Verify your email by opening the link below:
                %s

                If you did not create this account, you can ignore this email.
                """.formatted(verificationLink);
        String htmlBody = """
                <div style="font-family: Arial, sans-serif; color: #162033; line-height: 1.6;">
                  <h2 style="margin: 0 0 16px;">Verify your AuthX account</h2>
                  <p>Welcome to AuthX.</p>
                  <p>Click the button below to verify your email address:</p>
                  <p style="margin: 24px 0;">
                    <a href="%1$s" style="background: #1158ff; color: #ffffff; text-decoration: none; padding: 12px 18px; border-radius: 10px; display: inline-block; font-weight: 700;">Verify email</a>
                  </p>
                  <p>If the button does not work, open this link:</p>
                  <p><a href="%1$s">%1$s</a></p>
                  <p>If you did not create this account, you can ignore this email.</p>
                </div>
                """.formatted(verificationLink);

        sendEmail(email, subject, textBody, htmlBody);
    }

    public void sendPasswordResetEmail(String email, String token, String originHeader, String refererHeader) {
        String resetLink = buildLink("reset", token, originHeader, refererHeader);
        String subject = "Reset your AuthX password";
        String textBody = """
                We received a request to reset your AuthX password.

                Open the link below to choose a new password:
                %s

                If you did not request a password reset, you can ignore this email.
                """.formatted(resetLink);
        String htmlBody = """
                <div style="font-family: Arial, sans-serif; color: #162033; line-height: 1.6;">
                  <h2 style="margin: 0 0 16px;">Reset your AuthX password</h2>
                  <p>We received a request to reset your AuthX password.</p>
                  <p>Click the button below to choose a new password:</p>
                  <p style="margin: 24px 0;">
                    <a href="%1$s" style="background: #1158ff; color: #ffffff; text-decoration: none; padding: 12px 18px; border-radius: 10px; display: inline-block; font-weight: 700;">Reset password</a>
                  </p>
                  <p>If the button does not work, open this link:</p>
                  <p><a href="%1$s">%1$s</a></p>
                  <p>If you did not request a password reset, you can ignore this email.</p>
                </div>
                """.formatted(resetLink);

        sendEmail(email, subject, textBody, htmlBody);
    }

    private void sendEmail(String to, String subject, String textBody, String htmlBody) {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new EmailDeliveryException(
                    "Email delivery is not configured. Set MAIL_USERNAME, MAIL_PASSWORD, and optionally MAIL_FROM."
            );
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress.trim());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(textBody, htmlBody);
            mailSender.send(message);
        } catch (MailAuthenticationException exception) {
            throw new EmailDeliveryException(
                    "Email login failed. Check MAIL_USERNAME and MAIL_PASSWORD. For Gmail, use the app password without spaces.",
                    exception
            );
        } catch (MessagingException exception) {
            throw new EmailDeliveryException("Unable to build the email message right now.", exception);
        } catch (MailException exception) {
            throw new EmailDeliveryException("Unable to send email right now. Please try again.", exception);
        }
    }

    private String buildLink(String parameterName, String token, String originHeader, String refererHeader) {
        String normalizedUrl = normalizeBaseUrl(resolveBaseUrl(originHeader, refererHeader));
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
        return normalizedUrl + "?" + parameterName + "=" + encodedToken;
    }

    private String resolveBaseUrl(String originHeader, String refererHeader) {
        String originCandidate = extractOrigin(originHeader);
        if (originCandidate != null) {
            return originCandidate;
        }

        String refererCandidate = extractOrigin(refererHeader);
        if (refererCandidate != null) {
            return refererCandidate;
        }

        if (frontendUrl != null && !frontendUrl.isBlank()) {
            return frontendUrl.trim();
        }

        throw new EmailDeliveryException(
                "Frontend URL is not configured. Set FRONTEND_URL or send the request from the web app."
        );
    }

    private String extractOrigin(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            URI uri = new URI(value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }

            StringBuilder origin = new StringBuilder()
                    .append(uri.getScheme())
                    .append("://")
                    .append(uri.getHost());

            if (uri.getPort() != -1) {
                origin.append(":").append(uri.getPort());
            }

            return origin.toString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private String normalizeBaseUrl(String url) {
        return url.endsWith("/") ? url : url + "/";
    }
}
