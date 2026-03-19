package com.authx.authservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class SessionResponse {

    private final String sessionId;
    private final boolean current;
    private final LocalDateTime createdAt;
    private final LocalDateTime lastUsedAt;
    private final LocalDateTime expiresAt;
    private final String userAgent;
    private final String ipAddress;
}
