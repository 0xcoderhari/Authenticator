package com.authx.authservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class LockAccountRequest {

    @NotBlank(message = "Lock type is required")
    private String type; // "temporary" | "permanent"

    @Min(value = 1, message = "Duration must be at least 1 minute")
    private Integer durationMinutes;
}
