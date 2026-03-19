package com.authx.authservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
public class AuthResponse {

    private final String message;
    private final String token;
    private final Long expiresInSeconds;
    private final String sessionId;
    private final Long userId;
    private final String email;
    private final String role;

    private final boolean requires2fa;
    private final String preAuthToken;

    public AuthResponse(String message, String token) {
        this(message, token, null, null, null, null, null, false, null);
    }

    public AuthResponse(
            String message,
            String token,
            Long expiresInSeconds,
            String sessionId,
            Long userId,
            String email,
            String role,
            boolean requires2fa,
            String preAuthToken
    ) {
        this.message = message;
        this.token = token;
        this.expiresInSeconds = expiresInSeconds;
        this.sessionId = sessionId;
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.requires2fa = requires2fa;
        this.preAuthToken = preAuthToken;
    }
}
