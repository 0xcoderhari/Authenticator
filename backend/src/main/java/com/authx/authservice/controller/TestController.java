package com.authx.authservice.controller;

import com.authx.authservice.entity.ActionTokenPurpose;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.UserRepository;
import com.authx.authservice.service.ActionTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Test-only endpoint for latency measurement scripts.
 * Generates fresh action tokens (email verification, magic link, password reset)
 * that can be used directly by curl/k6 without SMTP interception.
 *
 * WARNING: Remove or disable in production.
 */
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final UserRepository userRepository;
    private final ActionTokenService actionTokenService;

    @PostMapping("/generate-token")
    public ResponseEntity<?> generateToken(@RequestBody GenerateTokenRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        ActionTokenPurpose purpose;
        try {
            purpose = ActionTokenPurpose.valueOf(request.purpose().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid purpose. Use: EMAIL_VERIFICATION, MAGIC_LINK, or PASSWORD_RESET");
        }

        String token = switch (purpose) {
            case EMAIL_VERIFICATION -> actionTokenService.createEmailVerificationToken(user);
            case PASSWORD_RESET     -> actionTokenService.createPasswordResetToken(user);
            case MAGIC_LINK         -> actionTokenService.createMagicLinkToken(user);
        };

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("isVerified", user.isVerified());
        response.put("twoFactorEnabled", user.isTwoFactorEnabled());

        if (user.isTwoFactorEnabled() && user.getTwoFactorSecret() != null) {
            response.put("totpSecret", user.getTwoFactorSecret());
        }

        return ResponseEntity.ok(response);
    }

    public record GenerateTokenRequest(long userId, String purpose) {}
}
