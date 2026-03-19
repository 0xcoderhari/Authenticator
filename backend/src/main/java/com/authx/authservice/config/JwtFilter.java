package com.authx.authservice.config;

import com.authx.authservice.entity.User;
import com.authx.authservice.repository.UserRepository;
import com.authx.authservice.service.JwtService;
import com.authx.authservice.service.RefreshTokenCookieService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RefreshTokenCookieService refreshTokenCookieService;
    private final com.authx.authservice.repository.RefreshTokenRepository refreshTokenRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = extractAccessToken(request);
        if (token.isEmpty() || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String username = jwtService.extractUsername(token);
            if (jwtService.validateToken(token)) {
                String sessionId = jwtService.extractSessionId(token);
                boolean isValidSession = true;
                
                if (sessionId != null) {
                    isValidSession = refreshTokenRepository.findBySessionId(sessionId)
                            .map(rt -> rt.getRevokedAt() == null)
                            .orElse(false);
                }
                
                if (isValidSession) {
                    userRepository.findByEmail(username)
                            .ifPresent(user -> setAuthentication(request, user));
                }
            }
        } catch (Exception exception) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private String extractAccessToken(HttpServletRequest request) {
        String cookieToken = refreshTokenCookieService.extractAccessToken(request);
        if (cookieToken != null && !cookieToken.isBlank()) {
            return cookieToken.trim();
        }

        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return "";
        }

        return authorizationHeader.substring(BEARER_PREFIX.length()).trim();
    }

    private void setAuthentication(HttpServletRequest request, User user) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        user.getEmail(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                );

        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
