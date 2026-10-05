package com.orthoflow.auth.application.service;

import com.orthoflow.auth.application.dto.AdminUserDtos.*;
import com.orthoflow.auth.domain.model.User;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.auth.infrastructure.adapter.persistence.UserJpaRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Admin-side user management: invite, change role, deactivate, force a reset.
 * Everything is scoped to the acting admin's clinic, and the last active ADMIN
 * can neither be demoted nor deactivated — otherwise the clinic could lock
 * itself out of its own settings.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserJpaRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final SessionService sessionService;

    @Transactional(readOnly = true)
    public List<UserRow> list(UUID practiceId) {
        return users.findByPracticeIdOrderByLastNameAscFirstNameAsc(practiceId).stream().map(UserAdminService::row).toList();
    }

    @Transactional
    public Invite invite(UUID practiceId, CreateUser request) {
        if (users.findByEmail(request.email().trim()).isPresent()) {
            throw new ConflictException("A user with this email already exists");
        }
        // The account is created with a password nobody knows; the invitee sets
        // their own through the reset link, so the admin never handles a password.
        User user = users.save(User.builder()
                .practiceId(practiceId)
                .email(request.email().trim())
                .passwordHash(passwordEncoder.encode(randomSecret()))
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .role(request.role())
                .active(true)
                .mustChangePassword(true)
                .build());
        return new Invite(row(user), authService.issueResetLink(user));
    }

    @Transactional
    public UserRow update(UUID practiceId, UUID actorId, UUID userId, UpdateUser request) {
        User user = load(practiceId, userId);
        boolean losesAdmin = user.getRole() == UserRole.ADMIN
                && user.isActive()
                && (request.role() != UserRole.ADMIN || !request.active());
        if (losesAdmin && users.countByPracticeIdAndRoleAndActiveTrue(practiceId, UserRole.ADMIN) <= 1) {
            throw new ConflictException("The clinic needs at least one active administrator");
        }
        if (user.getId().equals(actorId) && (!request.active() || request.role() != user.getRole())) {
            throw new ConflictException("You cannot change your own role or deactivate your own account");
        }
        boolean deactivating = user.isActive() && !request.active();
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setRole(request.role());
        user.setActive(request.active());
        if (deactivating) {
            sessionService.revokeAll(userId);
        }
        return row(users.save(user));
    }

    /** Signs the person out everywhere and sends them a fresh link to choose a new password. */
    @Transactional
    public Invite forceReset(UUID practiceId, UUID userId) {
        User user = load(practiceId, userId);
        user.setMustChangePassword(true);
        user.setSessionsValidAfter(OffsetDateTime.now());
        users.save(user);
        sessionService.revokeAll(userId);
        return new Invite(row(user), authService.issueResetLink(user));
    }

    private User load(UUID practiceId, UUID userId) {
        return users.findById(userId)
                .filter(u -> u.getPracticeId().equals(practiceId))
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private static UserRow row(User u) {
        return new UserRow(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getRole(),
                u.isActive(), u.isMustChangePassword(), u.getLastLoginAt());
    }

    private static String randomSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
