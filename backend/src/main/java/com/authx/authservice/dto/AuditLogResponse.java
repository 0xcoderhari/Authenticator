package com.authx.authservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class AuditLogResponse {

    private final Long id;
    private final Long userId;
    private final String email;
    private final String action;
    private final String ipAddress;
    private final String userAgent;
    private final String details;
    private final LocalDateTime createdAt;
}
