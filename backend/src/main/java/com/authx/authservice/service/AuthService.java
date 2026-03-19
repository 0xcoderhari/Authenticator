package com.authx.authservice.service;

import com.authx.authservice.dto.AuthResponse;
import com.authx.authservice.dto.AuthSessionResult;
import com.authx.authservice.dto.ForgotPasswordRequest;
import com.authx.authservice.dto.LoginRequest;
import com.authx.authservice.dto.ResendVerificationRequest;
import com.authx.authservice.dto.ResetPasswordRequest;
import com.authx.authservice.dto.SignupRequest;
import com.authx.authservice.entity.AuditAction;
import com.authx.authservice.entity.Role;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

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

    @Value("${app.security.max-failed-logins:5}")
    private int maxFailedLogins;

    @Value("${app.security.lockout-duration-minutes:15}")
    private long lockoutDurationMinutes;

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
            return new AuthSessionResult(new AuthResponse("Invalid email or password", null), null, null);
        }

        if (!user.isVerified()) {
            auditService.log(user.getId(), user.getEmail(), AuditAction.LOGIN_FAILURE, ipAddress, userAgent,
                    "Email not verified");
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
            String preAuthToken = jwtService.generateToken(user, "PRE_AUTH");
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
        return buildAuthSessionResult(
                user,
                refreshSession.session().getSessionId(),
                refreshSession.refreshToken(),
                "Login successful"
        );
    }

    @Transactional
    public AuthSessionResult verify2fa(String preAuthToken, String code, String ipAddress, String userAgent) {
        String email;
        try {
            email = jwtService.extractUsername(preAuthToken);
        } catch (Exception e) {
            auditService.log(null, null, AuditAction.LOGIN_FAILURE, ipAddress, userAgent, "Invalid pre-auth token");
            throw new IllegalArgumentException("Invalid authentication token. Please log in again.");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!user.isTwoFactorEnabled()) {
            throw new IllegalArgumentException("2FA is not enabled for this user.");
        }

        boolean isValid = twoFactorService.isOtpValid(user.getTwoFactorSecret(), code);
        if (!isValid) {
            auditService.log(user.getId(), email, AuditAction.TWO_FACTOR_FAILED, ipAddress, userAgent, "Invalid 2FA code during login");
            handleFailedLogin(user, ipAddress, userAgent);
            throw new IllegalArgumentException("Invalid 2-Factor Authentication code.");
        }

        RefreshTokenService.RefreshSessionIssue refreshSession = refreshTokenService.createSession(
                user,
                userAgent,
                ipAddress
        );

        auditService.log(user.getId(), email, AuditAction.LOGIN_SUCCESS, ipAddress, userAgent, "2FA Verified");
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
