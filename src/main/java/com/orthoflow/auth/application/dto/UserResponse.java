package com.orthoflow.auth.application.dto;

import com.orthoflow.auth.domain.model.UserRole;
import lombok.Builder;

import java.util.UUID;

@Builder
public record UserResponse(UUID id, String email, String firstName, String lastName, UserRole role,
                           boolean mustChangePassword) {
}
