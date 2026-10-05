package com.orthoflow.auth.application.dto;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.domain.model.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Request and response shapes for the user-administration and permission screens. */
public final class AdminUserDtos {

    private AdminUserDtos() {
    }

    public record CreateUser(@NotBlank @Email String email, @NotBlank String firstName,
                             @NotBlank String lastName, @NotNull UserRole role) {
    }

    public record UpdateUser(@NotBlank String firstName, @NotBlank String lastName,
                             @NotNull UserRole role, boolean active) {
    }

    public record UserRow(UUID id, String email, String firstName, String lastName, UserRole role,
                          boolean active, boolean mustChangePassword, OffsetDateTime lastLoginAt) {
    }

    /** {@code inviteUrl} is the one-time link to set a password, handed over when no mail transport delivers it. */
    public record Invite(UserRow user, String inviteUrl) {
    }

    public record PermissionMatrix(List<String> all, Map<UserRole, Set<Permission>> byRole, Set<UserRole> customised) {
    }

    public record SetPermissions(@NotNull Set<Permission> permissions) {
    }

    public record SessionRow(UUID id, OffsetDateTime createdAt, OffsetDateTime lastSeenAt, String ip,
                             String userAgent, boolean current) {
    }
}
