package com.authx.authservice.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RefreshTokenCookieService {

    private final String refreshCookieName;
    private final String accessCookieName;
    private final boolean secureCookies;
    private final String sameSite;
    private final String refreshCookiePath;
    private final String accessCookiePath;
    private final long accessTokenMinutes;
    private final long refreshTokenDays;

    public RefreshTokenCookieService(
            @Value("${app.security.refresh-cookie-name:refresh_token}") String refreshCookieName,
            @Value("${app.security.access-cookie-name:access_token}") String accessCookieName,
            @Value("${app.security.secure-cookies:false}") boolean secureCookies,
            @Value("${app.security.refresh-cookie-same-site:Lax}") String sameSite,
            @Value("${app.security.refresh-cookie-path:/api}") String refreshCookiePath,
            @Value("${app.security.access-cookie-path:/api}") String accessCookiePath,
            @Value("${app.security.access-token-minutes:15}") long accessTokenMinutes,
            @Value("${app.security.refresh-token-days:7}") long refreshTokenDays
    ) {
        this.refreshCookieName = refreshCookieName;
        this.accessCookieName = accessCookieName;
        this.secureCookies = secureCookies;
        this.sameSite = sameSite;
        this.refreshCookiePath = refreshCookiePath;
        this.accessCookiePath = accessCookiePath;
        this.accessTokenMinutes = accessTokenMinutes;
        this.refreshTokenDays = refreshTokenDays;
    }

    public void writeAccessTokenCookie(HttpServletResponse response, String accessToken) {
        ResponseCookie cookie = ResponseCookie.from(accessCookieName, accessToken)
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite(sameSite)
                .path(accessCookiePath)
                .maxAge(Duration.ofMinutes(accessTokenMinutes))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearAccessTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(accessCookieName, "")
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite(sameSite)
                .path(accessCookiePath)
                .maxAge(Duration.ZERO)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void writeRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, refreshToken)
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite(sameSite)
                .path(refreshCookiePath)
                .maxAge(Duration.ofDays(refreshTokenDays))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, "")
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite(sameSite)
                .path(refreshCookiePath)
                .maxAge(Duration.ZERO)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public String extractAccessToken(HttpServletRequest request) {
        return extractCookieValue(request, accessCookieName);
    }

    public String extractRefreshToken(HttpServletRequest request) {
        return extractCookieValue(request, refreshCookieName);
    }

    private String extractCookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }
}
