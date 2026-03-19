package com.authx.authservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class UserProfileResponse {

    private final Long id;
    private final String email;
    private final String role;
    private final boolean verified;
    private final boolean isTwoFactorEnabled;
    private final LocalDateTime createdAt;
}
