package com.authx.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class Verify2FaRequest {

    @NotBlank(message = "Pre-auth token is required")
    private String token;

    @NotBlank(message = "2FA code is required")
    private String code;
}
