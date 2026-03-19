package com.authx.authservice.service;

import com.authx.authservice.entity.RefreshToken;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String INVALID_REFRESH_TOKEN_MESSAGE = "The refresh token is invalid or has expired.";

    private final RefreshTokenRepository refreshTokenRepository;
    private final OpaqueTokenService opaqueTokenService;

    @Value("${app.security.refresh-token-days:7}")
    private long refreshTokenDays;

    @Transactional
    public RefreshSessionIssue createSession(User user, String userAgent, String ipAddress) {
        LocalDateTime now = LocalDateTime.now();
        OpaqueTokenService.IssuedToken issuedToken = opaqueTokenService.issueToken();

        RefreshToken session = refreshTokenRepository.save(RefreshToken.builder()
                .sessionId(issuedToken.tokenId())
                .tokenHash(issuedToken.tokenHash())
                .user(user)
                .createdAt(now)
                .lastUsedAt(now)
                .expiresAt(now.plusDays(refreshTokenDays))
                .userAgent(sanitizeUserAgent(userAgent))
                .ipAddress(sanitizeIpAddress(ipAddress))
                .build());

        return new RefreshSessionIssue(session, issuedToken.rawToken());
    }

    @Transactional
    public RefreshSessionIssue rotateSession(String rawToken, String userAgent, String ipAddress) {
        RefreshToken session = validateActiveSession(rawToken, true);
        LocalDateTime now = LocalDateTime.now();
        OpaqueTokenService.IssuedToken issuedToken = opaqueTokenService.issueToken(session.getSessionId());

        session.setTokenHash(issuedToken.tokenHash());
        session.setLastUsedAt(now);
        session.setExpiresAt(now.plusDays(refreshTokenDays));
        session.setUserAgent(sanitizeUserAgent(userAgent));
        session.setIpAddress(sanitizeIpAddress(ipAddress));
        refreshTokenRepository.save(session);

        return new RefreshSessionIssue(session, issuedToken.rawToken());
    }

    @Transactional
    public void revokeCurrentSession(String rawToken) {
        OpaqueTokenService.ParsedToken parsedToken = opaqueTokenService.parse(rawToken);
        if (parsedToken == null) {
            return;
        }

        refreshTokenRepository.findBySessionId(parsedToken.tokenId())
                .ifPresent(session -> revokeSessionIfMatches(session, parsedToken.rawToken()));
    }

    @Transactional
    public void revokeSession(User user, String sessionId) {
        RefreshToken session = refreshTokenRepository.findBySessionIdAndUser(sessionId, user)
                .orElseThrow(() -> new IllegalArgumentException("Session not found."));

        if (session.getRevokedAt() == null) {
            session.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(session);
        }
    }

    @Transactional
    public void revokeAllSessions(User user) {
        LocalDateTime now = LocalDateTime.now();
        List<RefreshToken> activeSessions = refreshTokenRepository.findByUserAndRevokedAtIsNull(user);

        boolean changed = false;
        for (RefreshToken session : activeSessions) {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(now);
                changed = true;
            }
        }

        if (changed) {
            refreshTokenRepository.saveAll(activeSessions);
        }
    }

    public List<RefreshToken> getActiveSessions(User user) {
        return refreshTokenRepository.findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(
                user,
                LocalDateTime.now()
        );
    }

    public String extractSessionId(String rawToken) {
        OpaqueTokenService.ParsedToken parsedToken = opaqueTokenService.parse(rawToken);
        return parsedToken != null ? parsedToken.tokenId() : null;
    }

    public long getRefreshTokenMaxAgeSeconds() {
        return Duration.ofDays(refreshTokenDays).toSeconds();
    }

    private RefreshToken validateActiveSession(String rawToken, boolean revokeOnMismatch) {
        OpaqueTokenService.ParsedToken parsedToken = opaqueTokenService.parse(rawToken);
        if (parsedToken == null) {
            throw new IllegalArgumentException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        RefreshToken session = refreshTokenRepository.findBySessionId(parsedToken.tokenId())
                .orElseThrow(() -> new IllegalArgumentException(INVALID_REFRESH_TOKEN_MESSAGE));

        LocalDateTime now = LocalDateTime.now();
        if (session.getRevokedAt() != null || session.getExpiresAt().isBefore(now)) {
            throw new IllegalArgumentException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        if (!opaqueTokenService.matches(parsedToken.rawToken(), session.getTokenHash())) {
            if (revokeOnMismatch) {
                revokeDetectedReplay(session, now);
            }
            throw new IllegalArgumentException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        return session;
    }

    private void revokeDetectedReplay(RefreshToken session, LocalDateTime now) {
        session.setRevokedAt(now);
        refreshTokenRepository.save(session);

        List<RefreshToken> activeSessions = refreshTokenRepository.findByUserAndRevokedAtIsNull(session.getUser());
        if (!activeSessions.isEmpty()) {
            activeSessions.forEach(activeSession -> activeSession.setRevokedAt(now));
            refreshTokenRepository.saveAll(activeSessions);
        }
    }

    private void revokeSessionIfMatches(RefreshToken session, String rawToken) {
        if (session.getRevokedAt() != null) {
            return;
        }

        if (opaqueTokenService.matches(rawToken, session.getTokenHash())) {
            session.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(session);
        }
    }

    private String sanitizeUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }

        String value = userAgent.trim();
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private String sanitizeIpAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }

        String value = ipAddress.trim();
        return value.length() > 128 ? value.substring(0, 128) : value;
    }

    public record RefreshSessionIssue(RefreshToken session, String refreshToken) {
    }
}
