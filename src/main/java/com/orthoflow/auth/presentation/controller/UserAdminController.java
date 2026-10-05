package com.orthoflow.auth.presentation.controller;

import com.orthoflow.auth.application.dto.AdminUserDtos.*;
import com.orthoflow.auth.application.service.RolePermissionService;
import com.orthoflow.auth.application.service.UserAdminService;
import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Settings → Users and Settings → Roles & permissions. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('USERS_MANAGE')")
public class UserAdminController {

    private final UserAdminService userAdminService;
    private final RolePermissionService rolePermissionService;
    private final CurrentUserProvider currentUser;

    @GetMapping("/users")
    public List<UserRow> users() {
        return userAdminService.list(currentUser.requirePracticeId());
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Invite invite(@Valid @RequestBody CreateUser request) {
        return userAdminService.invite(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/users/{id}")
    public UserRow update(@PathVariable UUID id, @Valid @RequestBody UpdateUser request) {
        return userAdminService.update(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request);
    }

    @PostMapping("/users/{id}/reset-password")
    public Invite forceReset(@PathVariable UUID id) {
        return userAdminService.forceReset(currentUser.requirePracticeId(), id);
    }

    @GetMapping("/permissions")
    public PermissionMatrix permissions() {
        UUID practiceId = currentUser.requirePracticeId();
        return new PermissionMatrix(
                Arrays.stream(Permission.values()).map(Enum::name).toList(),
                rolePermissionService.matrix(practiceId),
                rolePermissionService.customisedRoles(practiceId));
    }

    @PutMapping("/permissions/{role}")
    public PermissionMatrix setPermissions(@PathVariable UserRole role, @Valid @RequestBody SetPermissions request) {
        rolePermissionService.replace(currentUser.requirePracticeId(), role, request.permissions(), currentUser.requireUserId());
        return permissions();
    }

    @DeleteMapping("/permissions/{role}")
    public PermissionMatrix resetPermissions(@PathVariable UserRole role) {
        rolePermissionService.reset(currentUser.requirePracticeId(), role);
        return permissions();
    }
}
