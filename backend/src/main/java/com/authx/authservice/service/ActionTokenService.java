package com.authx.authservice.service;

import com.authx.authservice.entity.ActionToken;
import com.authx.authservice.entity.ActionTokenPurpose;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.ActionTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ActionTokenService {

    private final ActionTokenRepository actionTokenRepository;
    private final OpaqueTokenService opaqueTokenService;

    @Value("${app.security.email-verification-minutes:30}")
    private long emailVerificationMinutes;

    @Value("${app.security.password-reset-minutes:15}")
    private long passwordResetMinutes;

    @Value("${app.security.magic-link-minutes:15}")
    private long magicLinkMinutes;

    @Transactional
    public String createEmailVerificationToken(User user) {
        return createToken(user, ActionTokenPurpose.EMAIL_VERIFICATION, emailVerificationMinutes);
    }

    @Transactional
    public String createPasswordResetToken(User user) {
        return createToken(user, ActionTokenPurpose.PASSWORD_RESET, passwordResetMinutes);
    }

    @Transactional
    public String createMagicLinkToken(User user) {
        return createToken(user, ActionTokenPurpose.MAGIC_LINK, magicLinkMinutes);
    }

    @Transactional
    public User consumeEmailVerificationToken(String rawToken) {
        return consumeToken(
                rawToken,
                ActionTokenPurpose.EMAIL_VERIFICATION,
                "The verification link is invalid, expired, or already used."
        );
    }

    @Transactional
    public User consumePasswordResetToken(String rawToken) {
        return consumeToken(
                rawToken,
                ActionTokenPurpose.PASSWORD_RESET,
                "The password reset link is invalid, expired, or already used."
        );
    }

    @Transactional
    public User consumeMagicLinkToken(String rawToken) {
        return consumeToken(
                rawToken,
                ActionTokenPurpose.MAGIC_LINK,
                "The magic link is invalid, expired, or already used."
        );
    }

    private String createToken(User user, ActionTokenPurpose purpose, long expirationMinutes) {
        actionTokenRepository.deleteByUserAndPurpose(user, purpose);

        OpaqueTokenService.IssuedToken issuedToken = opaqueTokenService.issueToken();
        actionTokenRepository.save(ActionToken.builder()
                .tokenId(issuedToken.tokenId())
                .tokenHash(issuedToken.tokenHash())
                .user(user)
                .purpose(purpose)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(expirationMinutes))
                .build());

        return issuedToken.rawToken();
    }

    private User consumeToken(String rawToken, ActionTokenPurpose purpose, String errorMessage) {
        OpaqueTokenService.ParsedToken parsedToken = opaqueTokenService.parse(rawToken);
        if (parsedToken == null) {
            throw new IllegalArgumentException(errorMessage);
        }

        ActionToken actionToken = actionTokenRepository.findByTokenId(parsedToken.tokenId())
                .orElseThrow(() -> new IllegalArgumentException(errorMessage));

        LocalDateTime now = LocalDateTime.now();
        if (actionToken.getPurpose() != purpose
                || actionToken.getUsedAt() != null
                || actionToken.getExpiresAt().isBefore(now)
                || !opaqueTokenService.matches(parsedToken.rawToken(), actionToken.getTokenHash())) {
            throw new IllegalArgumentException(errorMessage);
        }

        actionToken.setUsedAt(now);
        actionTokenRepository.save(actionToken);
        return actionToken.getUser();
    }
}
