package com.authx.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyMagicLinkRequest {
    @NotBlank(message = "Token is required")
    private String token;
}
