package com.authx.authservice.service;

import com.authx.authservice.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {

    public static final String EMAIL_VERIFICATION_PURPOSE = "email-verification";
    public static final String PASSWORD_RESET_PURPOSE = "password-reset";

    private static final String PURPOSE_CLAIM = "purpose";
    private static final long AUTH_EXPIRATION_MINUTES = 15;
    private static final long EMAIL_VERIFICATION_EXPIRATION_HOURS = 24;
    private static final long PASSWORD_RESET_EXPIRATION_MINUTES = 30;

    private final SecretKey secretKey;

    public JwtService(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(AUTH_EXPIRATION_MINUTES, ChronoUnit.MINUTES)))
                .signWith(secretKey)
                .compact();
    }

    public String generateEmailVerificationToken(User user) {
        return generateActionToken(user, EMAIL_VERIFICATION_PURPOSE, EMAIL_VERIFICATION_EXPIRATION_HOURS, ChronoUnit.HOURS);
    }

    public String generatePasswordResetToken(User user) {
        return generateActionToken(user, PASSWORD_RESET_PURPOSE, PASSWORD_RESET_EXPIRATION_MINUTES, ChronoUnit.MINUTES);
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception exception) {
            return false;
        }
    }

    public String extractEmailFromActionToken(String token, String expectedPurpose) {
        try {
            Claims claims = extractAllClaims(token);
            String purpose = claims.get(PURPOSE_CLAIM, String.class);
            if (!expectedPurpose.equals(purpose) || claims.getExpiration().before(new Date())) {
                return null;
            }

            return claims.getSubject();
        } catch (Exception exception) {
            return null;
        }
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private String generateActionToken(User user, String purpose, long amountToAdd, ChronoUnit unit) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim(PURPOSE_CLAIM, purpose)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(amountToAdd, unit)))
                .signWith(secretKey)
                .compact();
    }
}
