package com.authx.authservice.service;

import com.authx.authservice.dto.AuthResponse;
import com.authx.authservice.dto.ForgotPasswordRequest;
import com.authx.authservice.dto.LoginRequest;
import com.authx.authservice.dto.ResetPasswordRequest;
import com.authx.authservice.dto.SignupRequest;
import com.authx.authservice.entity.Role;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern STRONG_PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,128}$");

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;

    @Transactional
    public String signup(SignupRequest request, String originHeader, String refererHeader) {
        validatePassword(request.getPassword(), request.getConfirmPassword());
        String email = normalizeEmail(request.getEmail());

        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .isVerified(false)
                .createdAt(LocalDateTime.now())
                .build();

        userRepository.save(user);
        mailService.sendVerificationEmail(
                user.getEmail(),
                jwtService.generateEmailVerificationToken(user),
                originHeader,
                refererHeader
        );

        return "Account created. Check your email to verify your account.";
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());

        return userRepository.findByEmail(email)
                .map(user -> buildLoginResponse(user, request.getPassword()))
                .orElse(new AuthResponse("Invalid email or password", null));
    }

    public String forgotPassword(ForgotPasswordRequest request, String originHeader, String refererHeader) {
        String email = normalizeEmail(request.getEmail());
        Optional<User> user = userRepository.findByEmail(email);
        user.ifPresent(existingUser -> mailService.sendPasswordResetEmail(
                existingUser.getEmail(),
                jwtService.generatePasswordResetToken(existingUser),
                originHeader,
                refererHeader
        ));

        return "If that email exists, a password reset link has been sent.";
    }

    @Transactional
    public String verifyEmail(String token) {
        String email = jwtService.extractEmailFromActionToken(token, JwtService.EMAIL_VERIFICATION_PURPOSE);
        if (email == null) {
            throw new IllegalArgumentException("The verification link is invalid or has expired.");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("The verification link is invalid or has expired."));

        if (user.isVerified()) {
            return "Email already verified. You can sign in.";
        }

        user.setVerified(true);
        userRepository.save(user);
        return "Email verified. You can sign in now.";
    }

    @Transactional
    public String resetPassword(ResetPasswordRequest request) {
        validatePassword(request.getPassword(), request.getConfirmPassword());

        String email = jwtService.extractEmailFromActionToken(request.getToken(), JwtService.PASSWORD_RESET_PURPOSE);
        if (email == null) {
            throw new IllegalArgumentException("The password reset link is invalid or has expired.");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("The password reset link is invalid or has expired."));

        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setVerified(true);
        userRepository.save(user);
        return "Password reset successful. You can sign in now.";
    }

    private boolean isPasswordValid(User user, String rawPassword) {
        return user.getPassword() != null && passwordEncoder.matches(rawPassword, user.getPassword());
    }

    private AuthResponse buildLoginResponse(User user, String rawPassword) {
        if (!isPasswordValid(user, rawPassword)) {
            return new AuthResponse("Invalid email or password", null);
        }

        if (!user.isVerified()) {
            return new AuthResponse("Verify your email before signing in.", null);
        }

        return new AuthResponse("Login successful", jwtService.generateToken(user));
    }

    private void validatePassword(String password, String confirmPassword) {
        if (password == null || confirmPassword == null) {
            throw new IllegalArgumentException("Password and confirm password are required");
        }

        if (!STRONG_PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalArgumentException(
                    "Password must be 8-128 characters and include uppercase, lowercase, number, and special character"
            );
        }

        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("Passwords do not match");
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
