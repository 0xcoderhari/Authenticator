package com.authx.authservice.controller;

import com.authx.authservice.dto.AuthResponse;
import com.authx.authservice.dto.AuthSessionResult;
import com.authx.authservice.dto.ForgotPasswordRequest;
import com.authx.authservice.dto.LoginRequest;
import com.authx.authservice.dto.ResendVerificationRequest;
import com.authx.authservice.dto.ResetPasswordRequest;
import com.authx.authservice.dto.SignupRequest;
import com.authx.authservice.service.AuthService;
import com.authx.authservice.service.RefreshTokenCookieService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieService refreshTokenCookieService;

    @PostMapping("/signup")
    public ResponseEntity<String> signup(
            @Valid @RequestBody SignupRequest request,
            @RequestHeader(value = "Origin", required = false) String originHeader,
            @RequestHeader(value = "Referer", required = false) String refererHeader,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.signup(request, originHeader, refererHeader,
                        extractClientIp(servletRequest), servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        AuthSessionResult result = authService.login(
                request,
                servletRequest.getHeader("User-Agent"),
                extractClientIp(servletRequest)
        );

        if (result.getAccessToken() != null) {
            refreshTokenCookieService.writeAccessTokenCookie(servletResponse, result.getAccessToken());
            refreshTokenCookieService.writeRefreshTokenCookie(servletResponse, result.getRefreshToken());
            return ResponseEntity.ok(result.getResponse());
        } else if (result.getResponse().isRequires2fa()) {
            return ResponseEntity.ok(result.getResponse());
        }

        return ResponseEntity.badRequest().body(result.getResponse());
    }

    @PostMapping("/verify-2fa")
    public ResponseEntity<AuthResponse> verify2Fa(
            @Valid @RequestBody com.authx.authservice.dto.Verify2FaRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        AuthSessionResult result = authService.verify2fa(
                request.getToken(),
                request.getCode(),
                extractClientIp(servletRequest),
                servletRequest.getHeader("User-Agent")
        );

        if (result.getAccessToken() != null) {
            refreshTokenCookieService.writeAccessTokenCookie(servletResponse, result.getAccessToken());
            refreshTokenCookieService.writeRefreshTokenCookie(servletResponse, result.getRefreshToken());
            return ResponseEntity.ok(result.getResponse());
        }

        return ResponseEntity.badRequest().body(result.getResponse());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        AuthSessionResult result = authService.refresh(
                refreshTokenCookieService.extractRefreshToken(servletRequest),
                servletRequest.getHeader("User-Agent"),
                extractClientIp(servletRequest)
        );

        refreshTokenCookieService.writeAccessTokenCookie(servletResponse, result.getAccessToken());
        refreshTokenCookieService.writeRefreshTokenCookie(servletResponse, result.getRefreshToken());
        return ResponseEntity.ok(result.getResponse());
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        authService.logout(
                refreshTokenCookieService.extractRefreshToken(servletRequest),
                extractClientIp(servletRequest),
                servletRequest.getHeader("User-Agent")
        );
        refreshTokenCookieService.clearAccessTokenCookie(servletResponse);
        refreshTokenCookieService.clearRefreshTokenCookie(servletResponse);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully."));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            @RequestHeader(value = "Origin", required = false) String originHeader,
            @RequestHeader(value = "Referer", required = false) String refererHeader,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(authService.forgotPassword(request, originHeader, refererHeader,
                extractClientIp(servletRequest), servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<String> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request,
            @RequestHeader(value = "Origin", required = false) String originHeader,
            @RequestHeader(value = "Referer", required = false) String refererHeader
    ) {
        return ResponseEntity.ok(authService.resendVerification(request, originHeader, refererHeader));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<String> verifyEmail(
            @RequestParam("token") String token,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(authService.verifyEmail(token,
                extractClientIp(servletRequest), servletRequest.getHeader("User-Agent")));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest servletRequest
    ) {
        return ResponseEntity.ok(authService.resetPassword(request,
                extractClientIp(servletRequest), servletRequest.getHeader("User-Agent")));
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }
}
