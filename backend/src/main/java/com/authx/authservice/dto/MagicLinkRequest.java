package com.authx.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MagicLinkRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Keep format standard, e.g. you@example.com")
    private String email;
}
