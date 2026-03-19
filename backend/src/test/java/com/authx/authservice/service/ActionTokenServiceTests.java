package com.authx.authservice.service;

import com.authx.authservice.entity.ActionToken;
import com.authx.authservice.entity.ActionTokenPurpose;
import com.authx.authservice.entity.Role;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.ActionTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionTokenServiceTests {

    @Mock
    private ActionTokenRepository actionTokenRepository;

    @Mock
    private OpaqueTokenService opaqueTokenService;

    @InjectMocks
    private ActionTokenService actionTokenService;

    @Test
    void emailVerificationTokenCanOnlyBeUsedOnce() {
        User user = buildUser();
        ActionToken token = ActionToken.builder()
                .tokenId("token-id")
                .tokenHash("token-hash")
                .user(user)
                .purpose(ActionTokenPurpose.EMAIL_VERIFICATION)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        when(opaqueTokenService.parse("token-id.secret"))
                .thenReturn(new OpaqueTokenService.ParsedToken("token-id", "token-id.secret"));
        when(actionTokenRepository.findByTokenId("token-id")).thenReturn(Optional.of(token));
        when(opaqueTokenService.matches("token-id.secret", "token-hash")).thenReturn(true);

        User consumedUser = actionTokenService.consumeEmailVerificationToken("token-id.secret");
        assertEquals(user.getEmail(), consumedUser.getEmail());
        assertNotNull(token.getUsedAt());
        verify(actionTokenRepository).save(token);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> actionTokenService.consumeEmailVerificationToken("token-id.secret")
        );

        assertEquals(
                "The verification link is invalid, expired, or already used.",
                exception.getMessage()
        );
    }

    @Test
    void passwordResetTokenRejectsExpiredLinks() {
        User user = buildUser();
        ActionToken token = ActionToken.builder()
                .tokenId("reset-id")
                .tokenHash("reset-hash")
                .user(user)
                .purpose(ActionTokenPurpose.PASSWORD_RESET)
                .createdAt(LocalDateTime.now().minusMinutes(20))
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();

        when(opaqueTokenService.parse("reset-id.secret"))
                .thenReturn(new OpaqueTokenService.ParsedToken("reset-id", "reset-id.secret"));
        when(actionTokenRepository.findByTokenId("reset-id")).thenReturn(Optional.of(token));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> actionTokenService.consumePasswordResetToken("reset-id.secret")
        );

        assertEquals(
                "The password reset link is invalid, expired, or already used.",
                exception.getMessage()
        );
    }

    @Test
    void createEmailVerificationTokenUsesConfiguredExpiryMinutes() {
        ReflectionTestUtils.setField(actionTokenService, "emailVerificationMinutes", 45L);
        User user = buildUser();

        OpaqueTokenService.IssuedToken issuedToken =
                new OpaqueTokenService.IssuedToken("new-id", "new-id.secret", "new-hash");
        when(opaqueTokenService.issueToken()).thenReturn(issuedToken);
        when(actionTokenRepository.save(any(ActionToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String rawToken = actionTokenService.createEmailVerificationToken(user);

        assertEquals("new-id.secret", rawToken);
        verify(actionTokenRepository).deleteByUserAndPurpose(user, ActionTokenPurpose.EMAIL_VERIFICATION);
        verify(actionTokenRepository).save(any(ActionToken.class));
    }

    private User buildUser() {
        return User.builder()
                .id(1L)
                .email("user@example.com")
                .role(Role.USER)
                .isVerified(false)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
