package com.authx.authservice.service;

import com.authx.authservice.dto.AuthResponse;
import com.authx.authservice.dto.AuthSessionResult;
import com.authx.authservice.dto.ForgotPasswordRequest;
import com.authx.authservice.dto.LoginRequest;
import com.authx.authservice.dto.MagicLinkRequest;
import com.authx.authservice.dto.ResendVerificationRequest;
import com.authx.authservice.dto.ResetPasswordRequest;
import com.authx.authservice.dto.SignupRequest;
import com.authx.authservice.entity.AuditAction;
import com.authx.authservice.entity.Role;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.UserRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern STRONG_PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,128}$");

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final RefreshTokenService refreshTokenService;
    private final ActionTokenService actionTokenService;
    private final AuditService auditService;
    private final TwoFactorService twoFactorService;
    private final LoginAlertService loginAlertService;

    @Value("${app.security.max-failed-logins:5}")
    private int maxFailedLogins;

    @Value("${app.security.lockout-duration-minutes:15}")
    private long lockoutDurationMinutes;

    @Value("${app.google.client-id:}")
    private String googleClientId;

    @Transactional
    public String signup(SignupRequest request, String originHeader, String refererHeader,
                         String ipAddress, String userAgent) {
        validatePassword(request.getPassword(), request.getConfirmPassword());
        String email = normalizeEmail(request.getEmail());

        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .isVerified(false)
                .createdAt(LocalDateTime.now())
                .build();

        userRepository.save(user);
        mailService.sendVerificationEmail(
                user.getEmail(),
                actionTokenService.createEmailVerificationToken(user),
                originHeader,
                refererHeader
        );

        auditService.log(user.getId(), email, AuditAction.SIGNUP, ipAddress, userAgent);
        return "Account created. Check your email to verify your account.";
    }

    @Transactional
    public AuthSessionResult login(LoginRequest request, String userAgent, String ipAddress) {
        String email = normalizeEmail(request.getEmail());

        Optional<User> optionalUser = userRepository.findByEmail(email);
        if (optionalUser.isEmpty()) {
            auditService.log(null, email, AuditAction.LOGIN_FAILURE, ipAddress, userAgent, "Unknown email");
            return new AuthSessionResult(new AuthResponse("Invalid email or password", null), null, null);
        }

        User user = optionalUser.get();

        // #region agent log
        debugLog("H1", "AuthService.login", "Login attempt for existing user", Map.of(
                "emailPresent", email != null && !email.isBlank(),
                "twoFactorEnabled", user.isTwoFactorEnabled()
        ));
        // #endregion

        // Check account lockout
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            long remainingMinutes = Duration.between(LocalDateTime.now(), user.getLockedUntil()).toMinutes() + 1;
            auditService.log(user.getId(), email, AuditAction.LOGIN_FAILURE, ipAddress, userAgent, "Account locked");
            return new AuthSessionResult(
                    new AuthResponse("Account is locked. Try again in " + remainingMinutes + " minute(s).", null),
                    null, null
            );
        }

        return buildLoginResponse(user, request.getPassword(), userAgent, ipAddress);
    }

    public AuthSessionResult refresh(String refreshToken, String userAgent, String ipAddress) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token is required.");
        }

        RefreshTokenService.RefreshSessionIssue refreshSession = refreshTokenService.rotateSession(
                refreshToken,
                userAgent,
                ipAddress
        );

        User user = refreshSession.session().getUser();
        if (!user.isVerified()) {
            refreshTokenService.revokeSession(user, refreshSession.session().getSessionId());
            throw new IllegalArgumentException("Verify your email before signing in.");
        }

        auditService.log(user.getId(), user.getEmail(), AuditAction.TOKEN_REFRESHED, ipAddress, userAgent);
        return buildAuthSessionResult(
                user,
                refreshSession.session().getSessionId(),
                refreshSession.refreshToken(),
                "Token refreshed."
        );
    }

    public void logout(String refreshToken, String ipAddress, String userAgent) {
        String sessionId = refreshTokenService.extractSessionId(refreshToken);
        refreshTokenService.revokeCurrentSession(refreshToken);
        auditService.log(null, null, AuditAction.LOGOUT, ipAddress, userAgent,
                sessionId != null ? "Session: " + sessionId : null);
    }

    public String resendVerification(
            ResendVerificationRequest request,
            String originHeader,
            String refererHeader
    ) {
        String email = normalizeEmail(request.getEmail());

        userRepository.findByEmail(email).ifPresent(user -> {
            if (!user.isVerified()) {
                mailService.sendVerificationEmail(
                        user.getEmail(),
                        actionTokenService.createEmailVerificationToken(user),
                        originHeader,
                        refererHeader
                );
            }
        });

        return "If that account exists and is not verified, a new verification email has been sent.";
    }

    public String forgotPassword(ForgotPasswordRequest request, String originHeader,
                                 String refererHeader, String ipAddress, String userAgent) {
        String email = normalizeEmail(request.getEmail());
        Optional<User> user = userRepository.findByEmail(email);
        user.ifPresent(existingUser -> mailService.sendPasswordResetEmail(
                existingUser.getEmail(),
                actionTokenService.createPasswordResetToken(existingUser),
                originHeader,
                refererHeader
        ));

        return "If that email exists, a password reset link has been sent.";
    }

    public String requestMagicLink(MagicLinkRequest request, String originHeader,
                                 String refererHeader, String ipAddress, String userAgent) {
        String email = normalizeEmail(request.getEmail());
        Optional<User> user = userRepository.findByEmail(email);
        user.ifPresent(existingUser -> mailService.sendMagicLinkEmail(
                existingUser.getEmail(),
                actionTokenService.createMagicLinkToken(existingUser),
                originHeader,
                refererHeader
        ));

        return "If that email exists, a magic link has been sent.";
    }

    @Transactional
    public AuthSessionResult verifyMagicLink(String token, String ipAddress, String userAgent) {
        User user = actionTokenService.consumeMagicLinkToken(token);
        
        // Reset lockout on successful login
        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        if (user.isTwoFactorEnabled()) {
            auditService.log(user.getId(), user.getEmail(), AuditAction.LOGIN_SUCCESS, ipAddress, userAgent, "2FA Required (Magic Link)");
            String preAuthToken = jwtService.generatePreAuthToken(user);
            return new AuthSessionResult(
                    new AuthResponse("2-Factor Authentication required.", null, null, null, null, null, null, true, preAuthToken),
                    null,
                    null
            );
        }

        RefreshTokenService.RefreshSessionIssue refreshSession = refreshTokenService.createSession(
                user,
                userAgent,
                ipAddress
        );

        auditService.log(user.getId(), user.getEmail(), AuditAction.LOGIN_SUCCESS, ipAddress, userAgent, "Magic Link Login");
        loginAlertService.checkAnomalousLogin(user, ipAddress, userAgent);
        return buildAuthSessionResult(
                user,
                refreshSession.session().getSessionId(),
                refreshSession.refreshToken(),
                "Login successful"
        );
    }

    @Transactional
    public String verifyEmail(String token, String ipAddress, String userAgent) {
        User user = actionTokenService.consumeEmailVerificationToken(token);

        if (user.isVerified()) {
            return "Email already verified. You can sign in.";
        }

        user.setVerified(true);
        userRepository.save(user);
        auditService.log(user.getId(), user.getEmail(), AuditAction.EMAIL_VERIFIED, ipAddress, userAgent);
        return "Email verified. You can sign in now.";
    }

    @Transactional
    public String resetPassword(ResetPasswordRequest request, String ipAddress, String userAgent) {
        validatePassword(request.getPassword(), request.getConfirmPassword());
        User user = actionTokenService.consumePasswordResetToken(request.getToken());

        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setVerified(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        refreshTokenService.revokeAllSessions(user);
        auditService.log(user.getId(), user.getEmail(), AuditAction.PASSWORD_RESET, ipAddress, userAgent);
        return "Password reset successful. You can sign in now.";
    }

    @Transactional
    public void unlockAccount(User user, String adminIp, String adminAgent) {
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        auditService.log(user.getId(), user.getEmail(), AuditAction.ACCOUNT_UNLOCKED, adminIp, adminAgent,
                "Unlocked by admin");
    }

    @Transactional
    public void lockAccountTemporarily(User user, long durationMinutes, String adminIp, String adminAgent) {
        user.setFailedLoginAttempts(maxFailedLogins);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(durationMinutes));
        userRepository.save(user);
        refreshTokenService.revokeAllSessions(user);
        auditService.log(user.getId(), user.getEmail(), AuditAction.ACCOUNT_LOCKED, adminIp, adminAgent,
                "Locked by admin for " + durationMinutes + " minutes");
    }

    @Transactional
    public void lockAccountPermanently(User user, String adminIp, String adminAgent) {
        user.setFailedLoginAttempts(maxFailedLogins);
        user.setLockedUntil(LocalDateTime.of(9999, 12, 31, 0, 0));
        userRepository.save(user);
        refreshTokenService.revokeAllSessions(user);
        auditService.log(user.getId(), user.getEmail(), AuditAction.ACCOUNT_LOCKED_PERMANENTLY, adminIp, adminAgent,
                "Locked permanently by admin");
    }

    @Transactional
    public AuthSessionResult googleLogin(String idToken, String userAgent, String ipAddress) {
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken googleIdToken = verifier.verify(idToken);
            if (googleIdToken == null) {
                throw new IllegalArgumentException("Invalid Google ID token.");
            }

            GoogleIdToken.Payload payload = googleIdToken.getPayload();
            String googleId = payload.getSubject();
            String email = (String) payload.getEmail();
            boolean emailVerified = payload.getEmailVerified();

            // Check account lock
            User user = userRepository.findByGoogleId(googleId)
                    .orElseGet(() -> userRepository.findByEmail(email).orElse(null));

            if (user != null && user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
                Duration remaining = Duration.between(LocalDateTime.now(), user.getLockedUntil());
                long minutes = remaining.toMinutes() + 1;
                return new AuthSessionResult(new AuthResponse(
                        "Account is locked. Try again in " + minutes + " minute(s).", null), null, null);
            }

            if (user != null) {
                // Link existing account with Google if not linked
                if (user.getGoogleId() == null) {
                    user.setGoogleId(googleId);
                }
                if (!user.isVerified() && emailVerified) {
                    user.setVerified(true);
                }
                user.setFailedLoginAttempts(0);
                userRepository.save(user);
            } else {
                // Create new user
                user = User.builder()
                        .email(email)
                        .googleId(googleId)
                        .role(Role.USER)
                        .isVerified(emailVerified)
                        .createdAt(LocalDateTime.now())
                        .build();
                userRepository.save(user);
            }

            auditService.log(user.getId(), user.getEmail(), AuditAction.GOOGLE_LOGIN_SUCCESS, ipAddress, userAgent,
                    "Google OAuth login");
            loginAlertService.checkAnomalousLogin(user, ipAddress, userAgent);

            return createSessionAndResponse(user, userAgent, ipAddress);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Google authentication failed. Please try again.");
        }
    }

    private AuthSessionResult createSessionAndResponse(User user, String userAgent, String ipAddress) {
        String accessToken = jwtService.generateToken(user, "GOOGLE_OAUTH");
        RefreshTokenService.RefreshSessionIssue session = refreshTokenService.createSession(user, userAgent, ipAddress);

        AuthResponse response = new AuthResponse(
                "Google login successful",
                null,
                jwtService.getAccessTokenTtlSeconds(),
                session.session().getSessionId(),
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                false,
                null
        );

        return new AuthSessionResult(response, accessToken, session.refreshToken());
    }

    private boolean isPasswordValid(User user, String rawPassword) {
        return user.getPassword() != null && passwordEncoder.matches(rawPassword, user.getPassword());
    }

    @Transactional
    private AuthSessionResult buildLoginResponse(
            User user,
            String rawPassword,
            String userAgent,
            String ipAddress
    ) {
        if (!isPasswordValid(user, rawPassword)) {
            handleFailedLogin(user, ipAddress, userAgent);
            // #region agent log
            debugLog("H2", "AuthService.buildLoginResponse", "Invalid password during login", Map.of(
                    "userId", user.getId(),
                    "twoFactorEnabled", user.isTwoFactorEnabled()
            ));
            // #endregion
            return new AuthSessionResult(new AuthResponse("Invalid email or password", null), null, null);
        }

        if (!user.isVerified()) {
            auditService.log(user.getId(), user.getEmail(), AuditAction.LOGIN_FAILURE, ipAddress, userAgent,
                    "Email not verified");
            // #region agent log
            debugLog("H3", "AuthService.buildLoginResponse", "Email not verified during login", Map.of(
                    "userId", user.getId(),
                    "twoFactorEnabled", user.isTwoFactorEnabled()
            ));
            // #endregion
            return new AuthSessionResult(new AuthResponse("Verify your email before signing in.", null), null, null);
        }

        // Reset lockout on successful login
        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        if (user.isTwoFactorEnabled()) {
            auditService.log(user.getId(), user.getEmail(), AuditAction.LOGIN_SUCCESS, ipAddress, userAgent, "2FA Required");
            String preAuthToken = jwtService.generatePreAuthToken(user);
            // #region agent log
            debugLog("H4", "AuthService.buildLoginResponse", "2FA required branch taken", Map.of(
                    "userId", user.getId()
            ));
            // #endregion
            return new AuthSessionResult(
                    new AuthResponse("2-Factor Authentication required.", null, null, null, null, null, null, true, preAuthToken),
                    null,
                    null
            );
        }

        RefreshTokenService.RefreshSessionIssue refreshSession = refreshTokenService.createSession(
                user,
                userAgent,
                ipAddress
        );

        auditService.log(user.getId(), user.getEmail(), AuditAction.LOGIN_SUCCESS, ipAddress, userAgent);
        loginAlertService.checkAnomalousLogin(user, ipAddress, userAgent);
        // #region agent log
        debugLog("H5", "AuthService.buildLoginResponse", "Password-only login successful", Map.of(
                "userId", user.getId(),
                "twoFactorEnabled", user.isTwoFactorEnabled()
        ));
        // #endregion
        return buildAuthSessionResult(
                user,
                refreshSession.session().getSessionId(),
                refreshSession.refreshToken(),
                "Login successful"
        );
    }

    @Transactional
    public AuthSessionResult verify2fa(String preAuthToken, String code, String ipAddress, String userAgent) {
        if (!jwtService.validatePreAuthToken(preAuthToken)) {
            auditService.log(null, null, AuditAction.LOGIN_FAILURE, ipAddress, userAgent, "Invalid pre-auth token");
            // #region agent log
            debugLog("H6", "AuthService.verify2fa", "Invalid pre-auth token when verifying 2FA", Map.of(
                    "hasToken", preAuthToken != null && !preAuthToken.isBlank()
            ));
            // #endregion
            throw new IllegalArgumentException("Invalid or expired authentication token. Please log in again.");
        }

        String email;
        try {
            email = jwtService.extractUsername(preAuthToken);
        } catch (Exception e) {
            auditService.log(null, null, AuditAction.LOGIN_FAILURE, ipAddress, userAgent, "Unreadable pre-auth token");
            throw new IllegalArgumentException("Invalid or expired authentication token. Please log in again.");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // #region agent log
        debugLog("H7", "AuthService.verify2fa", "2FA verification started for user", Map.of(
                "userId", user.getId(),
                "twoFactorEnabled", user.isTwoFactorEnabled()
        ));
        // #endregion

        if (!user.isTwoFactorEnabled()) {
            throw new IllegalArgumentException("2FA is not enabled for this user.");
        }

        boolean isValid = twoFactorService.isOtpValid(user.getTwoFactorSecret(), code);
        if (!isValid) {
            auditService.log(user.getId(), email, AuditAction.TWO_FACTOR_FAILED, ipAddress, userAgent, "Invalid 2FA code during login");
            handleFailedLogin(user, ipAddress, userAgent);
            // #region agent log
            debugLog("H8", "AuthService.verify2fa", "Invalid 2FA code during login", Map.of(
                    "userId", user.getId()
            ));
            // #endregion
            throw new IllegalArgumentException("Invalid 2-Factor Authentication code.");
        }

        RefreshTokenService.RefreshSessionIssue refreshSession = refreshTokenService.createSession(
                user,
                userAgent,
                ipAddress
        );

        auditService.log(user.getId(), email, AuditAction.LOGIN_SUCCESS, ipAddress, userAgent, "2FA Verified");
        loginAlertService.checkAnomalousLogin(user, ipAddress, userAgent);
        // #region agent log
        debugLog("H9", "AuthService.verify2fa", "2FA verification successful", Map.of(
                "userId", user.getId()
        ));
        // #endregion
        return buildAuthSessionResult(
                user,
                refreshSession.session().getSessionId(),
                refreshSession.refreshToken(),
                "Login successful"
        );
    }

    private void handleFailedLogin(User user, String ipAddress, String userAgent) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        String details = "Attempt " + attempts + "/" + maxFailedLogins;
        if (attempts >= maxFailedLogins) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(lockoutDurationMinutes));
            details += " — account locked for " + lockoutDurationMinutes + " minutes";
            auditService.log(user.getId(), user.getEmail(), AuditAction.ACCOUNT_LOCKED, ipAddress, userAgent, details);
        }

        userRepository.save(user);
        auditService.log(user.getId(), user.getEmail(), AuditAction.LOGIN_FAILURE, ipAddress, userAgent, details);
    }

    private AuthSessionResult buildAuthSessionResult(
            User user,
            String sessionId,
            String refreshToken,
            String message
    ) {
        String accessToken = jwtService.generateToken(user, sessionId);
        AuthResponse response = new AuthResponse(
                message,
                null,
                jwtService.getAccessTokenTtlSeconds(),
                sessionId,
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                false,
                null
        );

        return new AuthSessionResult(response, accessToken, refreshToken);
    }

    // #region agent log
    private void debugLog(String hypothesisId, String location, String message, Map<String, Object> data) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sessionId", "c345b9");
            payload.put("id", "log_" + Instant.now().toEpochMilli());
            payload.put("timestamp", Instant.now().toEpochMilli());
            payload.put("location", location);
            payload.put("message", message);
            payload.put("hypothesisId", hypothesisId);
            payload.put("runId", "pre-fix");
            payload.put("data", data);

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"sessionId\":\"").append("c345b9").append("\",");
            json.append("\"id\":\"").append(payload.get("id")).append("\",");
            json.append("\"timestamp\":").append(payload.get("timestamp")).append(",");
            json.append("\"location\":\"").append(location).append("\",");
            json.append("\"message\":\"").append(message.replace("\"", "\\\"")).append("\",");
            json.append("\"hypothesisId\":\"").append(hypothesisId).append("\",");
            json.append("\"runId\":\"pre-fix\",");
            json.append("\"data\":").append(data != null ? data.toString().replace("=", "\":\"").replace("{", "{\"").replace("}", "\"}") : "{}");
            json.append("}\n");

            Path path = Path.of("debug-c345b9.log");
            Files.writeString(path, json.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Swallow any logging errors to avoid impacting auth flow
        }
    }
    // #endregion

    private void validatePassword(String password, String confirmPassword) {
        if (password == null || confirmPassword == null) {
            throw new IllegalArgumentException("Password and confirm password are required");
        }

        if (!STRONG_PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalArgumentException(
                    "Password must be 8-128 characters and include uppercase, lowercase, number, and special character"
            );
        }

        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Passwords do not match");
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
