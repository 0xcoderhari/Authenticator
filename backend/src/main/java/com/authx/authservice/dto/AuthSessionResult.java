package com.authx.authservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthSessionResult {

    private final AuthResponse response;
    private final String accessToken;
    private final String refreshToken;
}
