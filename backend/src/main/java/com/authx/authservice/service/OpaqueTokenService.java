package com.authx.authservice.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class OpaqueTokenService {

    private final SecureRandom secureRandom = new SecureRandom();

    public IssuedToken issueToken() {
        return issueToken(UUID.randomUUID().toString());
    }

    public IssuedToken issueToken(String tokenId) {
        String secret = generateSecret();
        String rawToken = tokenId + "." + secret;
        return new IssuedToken(tokenId, rawToken, hash(rawToken));
    }

    public ParsedToken parse(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }

        int separatorIndex = rawToken.indexOf('.');
        if (separatorIndex <= 0 || separatorIndex == rawToken.length() - 1) {
            return null;
        }

        if (rawToken.indexOf('.', separatorIndex + 1) != -1) {
            return null;
        }

        return new ParsedToken(rawToken.substring(0, separatorIndex), rawToken);
    }

    public boolean matches(String rawToken, String expectedHash) {
        return expectedHash != null && expectedHash.equals(hash(rawToken));
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available on this JVM.", exception);
        }
    }

    public record IssuedToken(String tokenId, String rawToken, String tokenHash) {
    }

    public record ParsedToken(String tokenId, String rawToken) {
    }
}
