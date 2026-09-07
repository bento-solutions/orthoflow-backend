package com.orthoflow.auth.application.service;

import com.orthoflow.auth.application.dto.*;
import com.orthoflow.auth.application.port.PasswordResetNotifier;
import com.orthoflow.auth.domain.model.PasswordResetToken;
import com.orthoflow.auth.domain.model.User;
import com.orthoflow.auth.domain.repository.PasswordResetTokenRepository;
import com.orthoflow.auth.domain.repository.UserRepository;
import com.orthoflow.auth.infrastructure.security.JwtService;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.UnauthorizedException;
import com.orthoflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // Long enough that a reset link is only useful in the minutes after the
    // user actually requested it — an unused link left in an inbox for days
    // is an open door, not a convenience.
    private static final long RESET_TOKEN_TTL_MINUTES = 30;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordResetNotifier passwordResetNotifier;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!user.isActive() || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        return LoginResponse.builder()
                .token(token)
                .user(toResponse(user))
                .build();
    }

    /**
     * Bootstraps the first ADMIN account when no users exist yet, otherwise
     * requires an authenticated ADMIN to create further accounts
     * (enforced by @PreAuthorize on the controller, not here).
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("A user with this email already exists");
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .role(request.getRole())
                .active(true)
                .build();

        return toResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public boolean noUsersExist() {
        return !userRepository.existsAny();
    }

    /**
     * Always succeeds from the caller's point of view, whether or not the
     * email belongs to an account — a differing response here would let
     * anyone enumerate registered emails through this endpoint alone.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(email)
                .filter(User::isActive)
                .ifPresent(this::issueResetToken);
    }

    private void issueResetToken(User user) {
        // One live link per user: an old, forgotten link found later in an
        // inbox should not still work after a new one was requested.
        passwordResetTokenRepository.deleteAllForUser(user.getId());

        String rawToken = generateRawToken();
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(hashToken(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(RESET_TOKEN_TTL_MINUTES))
                .build();
        passwordResetTokenRepository.save(token);

        String resetUrl = frontendUrl + "/reset-password?token=" + rawToken;
        try {
            passwordResetNotifier.sendResetLink(user.getEmail(), resetUrl);
        } catch (RuntimeException e) {
            // The token is already persisted and still redeemable even if
            // delivery failed — log and move on rather than rolling back a
            // reset request over a transport-layer problem.
            log.error("Failed to send password reset link to {}", user.getEmail(), e);
        }
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(hashToken(request.getToken()))
                .filter(PasswordResetToken::isUsable)
                .orElseThrow(() -> new ValidationException("This password reset link is invalid or has expired"));

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ValidationException("This password reset link is invalid or has expired"));

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        // Kill every token minted before this reset — a reset is only
        // meaningful if it also logs out whoever knew the old password
        // (JwtAuthFilter checks token issuedAt against this).
        user.setSessionsValidAfter(OffsetDateTime.now());
        userRepository.save(user);

        token.setUsedAt(OffsetDateTime.now());
        passwordResetTokenRepository.save(token);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole())
                .build();
    }
}
