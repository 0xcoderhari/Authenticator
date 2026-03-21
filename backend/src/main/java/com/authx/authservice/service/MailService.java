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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    @Value("${app.mail.from:${spring.mail.username:}}")
    private String fromAddress;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${app.frontend-url:}")
    private String frontendUrl;

    @Value("${app.security.email-verification-minutes:30}")
    private long emailVerificationMinutes;

    @Value("${app.security.password-reset-minutes:15}")
    private long passwordResetMinutes;

    @Value("${app.security.magic-link-minutes:15}")
    private long magicLinkMinutes;

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
        String verificationExpiryText = formatDuration(emailVerificationMinutes);
        String subject = "Verify your AuthX account";
        String textBody = """
                Welcome to AuthX.

                Verify your email by opening the link below:
                %s

                This link expires in %s and can only be used once.

                If you did not create this account, you can ignore this email.
                """.formatted(verificationLink, verificationExpiryText);
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
                  <p>This link expires in %2$s and can only be used once.</p>
                  <p>If you did not create this account, you can ignore this email.</p>
                </div>
                """.formatted(verificationLink, verificationExpiryText);

        sendEmail(email, subject, textBody, htmlBody);
    }

    public void sendPasswordResetEmail(String email, String token, String originHeader, String refererHeader) {
        String resetLink = buildLink("reset", token, originHeader, refererHeader);
        String passwordResetExpiryText = formatDuration(passwordResetMinutes);
        String subject = "Reset your AuthX password";
        String textBody = """
                We received a request to reset your AuthX password.

                Open the link below to choose a new password:
                %s

                This link expires in %s and can only be used once.

                If you did not request a password reset, you can ignore this email.
                """.formatted(resetLink, passwordResetExpiryText);
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
                  <p>This link expires in %2$s and can only be used once.</p>
                  <p>If you did not request a password reset, you can ignore this email.</p>
                </div>
                """.formatted(resetLink, passwordResetExpiryText);

        sendEmail(email, subject, textBody, htmlBody);
    }

    public void sendMagicLinkEmail(String email, String token, String originHeader, String refererHeader) {
        String magicLink = buildLink("magic", token, originHeader, refererHeader);
        String magicLinkExpiryText = formatDuration(magicLinkMinutes);
        String subject = "Sign in to AuthX";
        String textBody = """
                Welcome back to AuthX.

                Open the link below to securely sign in to your account:
                %s

                This link expires in %s and can only be used once.

                If you did not request this link, you can safely ignore this email.
                """.formatted(magicLink, magicLinkExpiryText);
        String htmlBody = """
                <div style="font-family: Arial, sans-serif; color: #162033; line-height: 1.6;">
                  <h2 style="margin: 0 0 16px;">Sign in to AuthX</h2>
                  <p>Welcome back to AuthX.</p>
                  <p>Click the button below to securely sign in to your account:</p>
                  <p style="margin: 24px 0;">
                    <a href="%1$s" style="background: #1158ff; color: #ffffff; text-decoration: none; padding: 12px 18px; border-radius: 10px; display: inline-block; font-weight: 700;">Sign in to AuthX</a>
                  </p>
                  <p>If the button does not work, open this link:</p>
                  <p><a href="%1$s">%1$s</a></p>
                  <p>This link expires in %2$s and can only be used once.</p>
                  <p>If you did not request this link, you can safely ignore this email.</p>
                </div>
                """.formatted(magicLink, magicLinkExpiryText);

        sendEmail(email, subject, textBody, htmlBody);
    }

    private void sendEmail(String to, String subject, String textBody, String htmlBody) {
        com.authx.authservice.dto.EmailTask task = new com.authx.authservice.dto.EmailTask(to, subject, textBody, htmlBody);
        redisTemplate.opsForList().leftPush("email:queue", task);
    }

    public void executeSendEmail(String to, String subject, String textBody, String htmlBody) {
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

    private String formatDuration(long minutes) {
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }

        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        if (remainingMinutes == 0) {
            return hours + (hours == 1 ? " hour" : " hours");
        }

        return hours
                + (hours == 1 ? " hour " : " hours ")
                + remainingMinutes
                + (remainingMinutes == 1 ? " minute" : " minutes");
    }

    public void sendNewDeviceAlert(String to, String location, String device, LocalDateTime time) {
        String subject = "New Login Alert - AuthX";
        String timeStr = time.toString();
        
        String htmlBody = """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; line-height: 1.6; color: #333; margin: 0; padding: 0; background-color: #f9fbfd; }
                    .container { max-width: 600px; margin: 40px auto; background: white; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.05); }
                    .header { background-color: #f43f5e; padding: 30px; text-align: center; }
                    .header h1 { color: white; margin: 0; font-size: 24px; font-weight: 600; }
                    .content { padding: 40px; }
                    .alert-box { background-color: #fff1f2; border: 1px solid #fda4af; border-left: 4px solid #e11d48; padding: 15px 20px; border-radius: 6px; margin: 20px 0; }
                    .footer { text-align: center; padding: 20px; font-size: 13px; color: #64748b; background-color: #f8fafc; border-top: 1px solid #e2e8f0; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>Security Alert</h1>
                    </div>
                    <div class="content">
                        <h2>New Login Detected</h2>
                        <p>We noticed a new login to your AuthX account from an unfamiliar device or location.</p>
                        
                        <div class="alert-box">
                            <ul style="list-style: none; padding: 0; margin: 0; font-size: 15px;">
                                <li style="margin-bottom: 8px;"><strong>Device:</strong> %s</li>
                                <li style="margin-bottom: 8px;"><strong>Location:</strong> %s</li>
                                <li><strong>Time:</strong> %s</li>
                            </ul>
                        </div>
                        
                        <p>If this was you, you can safely ignore this email.</p>
                        <p style="font-weight: 500; color: #e11d48;">If you don't recognize this activity, please sign in and change your password immediately.</p>
                    </div>
                    <div class="footer">
                        &copy; %d AuthX. This is an automated security alert.
                    </div>
                </div>
            </body>
            </html>
            """.formatted(device, location, timeStr, LocalDate.now().getYear());

        String textBody = String.format("New Login Alert - AuthX\n\nWe noticed a new login from an unfamiliar device/location.\nDevice: %s\nLocation: %s\nTime: %s\n\nIf you don't recognize this, change your password immediately.", device, location, timeStr);

        sendEmail(to, subject, textBody, htmlBody);
    }
}
