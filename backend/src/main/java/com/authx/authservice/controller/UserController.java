package com.authx.authservice.controller;

import com.authx.authservice.dto.SessionResponse;
import com.authx.authservice.dto.UserProfileResponse;
import com.authx.authservice.entity.AuditAction;
import com.authx.authservice.entity.RefreshToken;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.UserRepository;
import com.authx.authservice.service.AuditService;
import com.authx.authservice.service.RefreshTokenCookieService;
import com.authx.authservice.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieService refreshTokenCookieService;
    private final AuditService auditService;
    private final com.authx.authservice.service.TwoFactorService twoFactorService;

    @GetMapping("/me")
    public UserProfileResponse me(Authentication authentication) {
        return profile(authentication);
    }

    @GetMapping("/profile")
    public UserProfileResponse profile(Authentication authentication) {
        return toProfileResponse(getCurrentUser(authentication));
    }

    @GetMapping("/sessions")
    public List<SessionResponse> sessions(Authentication authentication, HttpServletRequest request) {
        User currentUser = getCurrentUser(authentication);
        String currentSessionId = refreshTokenService.extractSessionId(
                refreshTokenCookieService.extractRefreshToken(request)
        );

        return refreshTokenService.getActiveSessions(currentUser).stream()
                .map(session -> toSessionResponse(session, session.getSessionId().equals(currentSessionId)))
                .toList();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> revokeSession(
            @PathVariable String sessionId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        User user = getCurrentUser(authentication);
        refreshTokenService.revokeSession(user, sessionId);

        auditService.log(user.getId(), user.getEmail(), AuditAction.SESSION_REVOKED,
                extractClientIp(request), request.getHeader("User-Agent"),
                "Session: " + sessionId);

        String currentSessionId = refreshTokenService.extractSessionId(
                refreshTokenCookieService.extractRefreshToken(request)
        );
        if (sessionId.equals(currentSessionId)) {
            refreshTokenCookieService.clearAccessTokenCookie(response);
            refreshTokenCookieService.clearRefreshTokenCookie(response);
        }

        return Map.of("message", "Session logged out.");
    }

    @PostMapping("/sessions/logout-all")
    public Map<String, String> logoutAll(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        User user = getCurrentUser(authentication);
        refreshTokenService.revokeAllSessions(user);

        auditService.log(user.getId(), user.getEmail(), AuditAction.ALL_SESSIONS_REVOKED,
                extractClientIp(request), request.getHeader("User-Agent"));

        refreshTokenCookieService.clearAccessTokenCookie(response);
        refreshTokenCookieService.clearRefreshTokenCookie(response);
        return Map.of("message", "All sessions logged out.");
    }

    @PostMapping("/2fa/generate")
    public Map<String, String> generate2FA(Authentication authentication) {
        User user = getCurrentUser(authentication);
        String secret = twoFactorService.generateNewSecret();
        user.setTwoFactorSecret(secret);
        userRepository.save(user);

        String qrCodeUri = twoFactorService.generateQrCodeImageUri(secret, user.getEmail());
        return Map.of("secret", secret, "qrCode", qrCodeUri);
    }

    @PostMapping("/2fa/enable")
    public Map<String, String> enable2FA(@RequestBody Map<String, String> requestData, Authentication authentication, HttpServletRequest request) {
        String code = requestData.get("code");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("2FA code is required.");
        }

        User user = getCurrentUser(authentication);
        if (user.isTwoFactorEnabled()) {
            throw new IllegalArgumentException("2FA is already enabled.");
        }

        if (user.getTwoFactorSecret() == null) {
            throw new IllegalArgumentException("2FA secret not generated. Please generate first.");
        }

        boolean isValid = twoFactorService.isOtpValid(user.getTwoFactorSecret(), code);
        if (!isValid) {
            auditService.log(user.getId(), user.getEmail(), AuditAction.TWO_FACTOR_FAILED, extractClientIp(request), request.getHeader("User-Agent"));
            throw new IllegalArgumentException("Invalid 2FA code.");
        }

        user.setTwoFactorEnabled(true);
        userRepository.save(user);

        auditService.log(user.getId(), user.getEmail(), AuditAction.TWO_FACTOR_ENABLED, extractClientIp(request), request.getHeader("User-Agent"));
        return Map.of("message", "2-Factor Authentication enabled successfully.");
    }

    @PostMapping("/2fa/disable")
    public Map<String, String> disable2FA(
            @RequestBody Map<String, String> requestData,
            Authentication authentication,
            HttpServletRequest request
    ) {
        String code = requestData.get("code");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("2FA code is required.");
        }

        User user = getCurrentUser(authentication);
        if (!user.isTwoFactorEnabled()) {
            throw new IllegalArgumentException("2FA is not enabled.");
        }

        if (user.getTwoFactorSecret() == null) {
            throw new IllegalArgumentException("2FA secret not available for this user.");
        }

        boolean isValid = twoFactorService.isOtpValid(user.getTwoFactorSecret(), code);
        if (!isValid) {
            auditService.log(user.getId(), user.getEmail(), AuditAction.TWO_FACTOR_FAILED,
                    extractClientIp(request), request.getHeader("User-Agent"),
                    "Invalid 2FA code when disabling 2FA");
            throw new IllegalArgumentException("Invalid 2FA code.");
        }

        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);

        auditService.log(user.getId(), user.getEmail(), AuditAction.TWO_FACTOR_DISABLED,
                extractClientIp(request), request.getHeader("User-Agent"));
        return Map.of("message", "2-Factor Authentication disabled.");
    }

    private User getCurrentUser(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
    }

    private UserProfileResponse toProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .verified(user.isVerified())
                .isTwoFactorEnabled(user.isTwoFactorEnabled())
                .locked(user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now()))
                .createdAt(user.getCreatedAt())
                .build();
    }

    private SessionResponse toSessionResponse(RefreshToken session, boolean current) {
        return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .current(current)
                .createdAt(session.getCreatedAt())
                .lastUsedAt(session.getLastUsedAt())
                .expiresAt(session.getExpiresAt())
                .userAgent(session.getUserAgent())
                .ipAddress(session.getIpAddress())
                .build();
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
