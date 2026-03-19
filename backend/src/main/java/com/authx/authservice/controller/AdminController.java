package com.authx.authservice.controller;

import com.authx.authservice.dto.AdminSessionResponse;
import com.authx.authservice.dto.AuditLogResponse;
import com.authx.authservice.dto.LockAccountRequest;
import com.authx.authservice.dto.UserProfileResponse;
import com.authx.authservice.entity.AuditLog;
import com.authx.authservice.entity.RefreshToken;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.RefreshTokenRepository;
import com.authx.authservice.repository.UserRepository;
import com.authx.authservice.service.AuditService;
import com.authx.authservice.service.AuthService;
import com.authx.authservice.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final AuthService authService;

    @GetMapping("/users")
    public List<UserProfileResponse> users(@RequestParam(name = "q", required = false) String query) {
        List<User> users;
        if (query == null || query.isBlank()) {
            users = userRepository.findAll();
        } else {
            users = userRepository.findTop50ByEmailContainingIgnoreCaseOrderByCreatedAtDesc(query.trim());
        }

        return users.stream()
                .map(this::toProfileResponse)
                .toList();
    }

    @GetMapping("/sessions")
    public List<AdminSessionResponse> allSessions() {
        return refreshTokenRepository
                .findByRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(LocalDateTime.now())
                .stream()
                .map(this::toAdminSessionResponse)
                .toList();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> revokeAnySession(@PathVariable String sessionId) {
        RefreshToken session = refreshTokenRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found."));
        refreshTokenService.revokeSession(session.getUser(), sessionId);
        return Map.of("message", "Session revoked.");
    }

    @GetMapping("/audit-logs")
    public Page<AuditLogResponse> auditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Long userId
    ) {
        Page<AuditLog> logs = userId != null
                ? auditService.getLogsByUser(userId, page, size)
                : auditService.getAllLogs(page, size);

        return logs.map(this::toAuditLogResponse);
    }

    @PostMapping("/users/{userId}/unlock")
    public Map<String, String> unlockUser(
            @PathVariable Long userId,
            HttpServletRequest request
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        authService.unlockAccount(user,
                extractClientIp(request), request.getHeader("User-Agent"));
        return Map.of("message", "Account unlocked.");
    }

    @PostMapping("/users/{userId}/lock")
    public Map<String, String> lockUser(
            @PathVariable Long userId,
            @Valid @RequestBody LockAccountRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        String adminEmail = authentication.getName();
        if (adminEmail.equals(user.getEmail())) {
            throw new IllegalArgumentException("You cannot lock your own account.");
        }

        String type = request.getType().toLowerCase();
        String ip = extractClientIp(servletRequest);
        String agent = servletRequest.getHeader("User-Agent");

        switch (type) {
            case "permanent" -> {
                authService.lockAccountPermanently(user, ip, agent);
                return Map.of("message", "Account locked permanently.", "lockType", "permanent");
            }
            case "temporary" -> {
                long duration = request.getDurationMinutes() != null ? request.getDurationMinutes() : 15;
                authService.lockAccountTemporarily(user, duration, ip, agent);
                return Map.of("message", "Account locked for " + duration + " minutes.", "lockType", "temporary");
            }
            default -> throw new IllegalArgumentException("Invalid lock type. Use 'temporary' or 'permanent'.");
        }
    }

    private UserProfileResponse toProfileResponse(User user) {
        boolean isLocked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now());
        String lockType = "none";
        if (isLocked) {
            if (user.getLockedUntil().getYear() >= 9999) {
                lockType = "permanent";
            } else if (user.getFailedLoginAttempts() >= 5) {
                lockType = "auto";
            } else {
                lockType = "temporary";
            }
        }
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .verified(user.isVerified())
                .isTwoFactorEnabled(user.isTwoFactorEnabled())
                .locked(isLocked)
                .lockType(lockType)
                .createdAt(user.getCreatedAt())
                .build();
    }

    private AdminSessionResponse toAdminSessionResponse(RefreshToken session) {
        User user = session.getUser();
        return AdminSessionResponse.builder()
                .sessionId(session.getSessionId())
                .userId(user.getId())
                .email(user.getEmail())
                .createdAt(session.getCreatedAt())
                .lastUsedAt(session.getLastUsedAt())
                .expiresAt(session.getExpiresAt())
                .userAgent(session.getUserAgent())
                .ipAddress(session.getIpAddress())
                .build();
    }

    private AuditLogResponse toAuditLogResponse(AuditLog log) {
        return AuditLogResponse.builder()
                .id(log.getId())
                .userId(log.getUserId())
                .email(log.getEmail())
                .action(log.getAction().name())
                .ipAddress(log.getIpAddress())
                .userAgent(log.getUserAgent())
                .details(log.getDetails())
                .createdAt(log.getCreatedAt())
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
