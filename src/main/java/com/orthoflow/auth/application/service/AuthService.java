package com.orthoflow.auth.application.service;

import com.orthoflow.auth.application.dto.*;
import com.orthoflow.auth.application.port.PasswordResetNotifier;
import com.orthoflow.auth.domain.model.PasswordResetToken;
import com.orthoflow.auth.domain.model.User;
import com.orthoflow.auth.domain.repository.PasswordResetTokenRepository;
import com.orthoflow.auth.domain.repository.UserRepository;
import com.orthoflow.auth.infrastructure.adapter.persistence.UserJpaRepository;
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
import java.util.UUID;
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
    private final SessionService sessionService;
    private final UserJpaRepository userJpaRepository;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        return login(request, null, null);
    }

    /** As {@link #login(LoginRequest)}, recording where the sign-in came from so "My account" can list it. */
    @Transactional
    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!user.isActive() || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);
        var session = sessionService.open(user.getId(), ip, userAgent);
        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name(), session.getId());
        return LoginResponse.builder()
                .token(token)
                .user(toResponse(user))
                .build();
    }

    /**
     * Exchanges a live token for a later one, keeping the original sign-in
     * time so the bound in {@link JwtService} holds however often it is
     * renewed. The account is re-read: a deactivated user is refused here as
     * well as by the filter that let the request in.
     */
    @Transactional(readOnly = true)
    public LoginResponse refresh(UUID userId, String presentedToken) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new UnauthorizedException("This session is no longer valid. Sign in again."));
        String token = jwtService.refreshToken(presentedToken, user.getId(), user.getEmail(), user.getRole().name());
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
    public UserResponse register(RegisterRequest request, UUID practiceId) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("A user with this email already exists");
        }

        User user = User.builder()
                .practiceId(practiceId)
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

    /**
     * Mints a one-live-link reset token and tells the user. Returns the link so
     * an admin who created the account (or forced a reset) can hand it over
     * directly when no mail transport is configured.
     */
    public String issueResetLink(User user) {
        return issueResetToken(user);
    }

    private String issueResetToken(User user) {
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
        return resetUrl;
    }

    /**
     * Changing one's own password ends every other sign-in: whoever had the old
     * password should not stay signed in. The current device keeps its session.
     */
    @Transactional
    public void changePassword(UUID userId, UUID currentSessionId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("This session is no longer valid. Sign in again."));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ValidationException("The current password is not correct");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ValidationException("Choose a password different from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        if (currentSessionId != null) {
            sessionService.revokeOthers(userId, currentSessionId);
        } else {
            sessionService.revokeAll(userId);
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
        user.setMustChangePassword(false);
        userRepository.save(user);
        sessionService.revokeAll(user.getId());

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
                .mustChangePassword(user.isMustChangePassword())
                .build();
    }
}
