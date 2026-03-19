package com.authx.authservice.service;

import com.authx.authservice.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {

    private static final String TOKEN_TYPE_CLAIM = "type";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String PRE_AUTH_TOKEN_TYPE = "pre_auth";

    private final SecretKey secretKey;
    private final long accessExpirationMinutes;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${app.security.access-token-minutes:15}") long accessExpirationMinutes
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMinutes = accessExpirationMinutes;
    }

    public String generateToken(User user, String sessionId) {
        return generateToken(user, sessionId, ACCESS_TOKEN_TYPE);
    }

    public String generatePreAuthToken(User user) {
        return generateToken(user, "PRE_AUTH", PRE_AUTH_TOKEN_TYPE);
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractSessionId(String token) {
        return extractAllClaims(token).get("sessionId", String.class);
    }

    public boolean validateToken(String token) {
        return validateTokenType(token, ACCESS_TOKEN_TYPE);
    }

    public boolean validatePreAuthToken(String token) {
        return validateTokenType(token, PRE_AUTH_TOKEN_TYPE);
    }

    public long getAccessTokenTtlSeconds() {
        return Duration.ofMinutes(accessExpirationMinutes).toSeconds();
    }

    private String generateToken(User user, String sessionId, String tokenType) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(user.getEmail())
                .claim(TOKEN_TYPE_CLAIM, tokenType)
                .claim("userId", user.getId())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("sessionId", sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessExpirationMinutes, ChronoUnit.MINUTES)))
                .signWith(secretKey)
                .compact();
    }

    private boolean validateTokenType(String token, String expectedType) {
        try {
            Claims claims = extractAllClaims(token);
            return expectedType.equals(claims.get(TOKEN_TYPE_CLAIM, String.class))
                    && claims.getExpiration().after(new Date());
        } catch (Exception exception) {
            return false;
        }
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
