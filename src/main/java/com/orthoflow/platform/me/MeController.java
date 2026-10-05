package com.orthoflow.platform.me;

import com.orthoflow.auth.application.dto.AdminUserDtos.SessionRow;
import com.orthoflow.auth.application.dto.ChangePasswordRequest;
import com.orthoflow.auth.application.service.AuthService;
import com.orthoflow.auth.application.service.SessionService;
import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.infrastructure.security.JwtService;
import com.orthoflow.common.security.AuthenticatedUser;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.team.application.service.PractitionerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "My account": who am I and what may I do, change my password, see and end my
 * sign-ins. Lives in {@code platform} because it composes auth with the team
 * module (which practitioner am I), and platform is the one package allowed to
 * depend on every module.
 */
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

    public record Me(UUID id, String email, String role, UUID practiceId, List<String> permissions,
                     UUID practitionerId) {
    }

    private final CurrentUserProvider currentUser;
    private final PractitionerService practitionerService;
    private final SessionService sessionService;
    private final AuthService authService;
    private final JwtService jwtService;

    @GetMapping
    public Me me() {
        AuthenticatedUser user = currentUser.require();
        Set<String> names = Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toSet());
        List<String> permissions = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(names::contains)
                .sorted()
                .toList();
        UUID practitionerId = practitionerService.findIdByUser(user.id()).orElse(null);
        return new Me(user.id(), user.email(), user.role(), user.practiceId(), permissions, practitionerId);
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request,
                               @RequestHeader("Authorization") String authorization) {
        authService.changePassword(currentUser.requireUserId(), sessionIdOf(authorization),
                request.getCurrentPassword(), request.getNewPassword());
    }

    @GetMapping("/sessions")
    public List<SessionRow> sessions(@RequestHeader("Authorization") String authorization) {
        UUID current = sessionIdOf(authorization);
        return sessionService.active(currentUser.requireUserId()).stream()
                .map(s -> new SessionRow(s.getId(), s.getCreatedAt(), s.getLastSeenAt(), s.getIp(),
                        s.getUserAgent(), s.getId().equals(current)))
                .toList();
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endSession(@PathVariable UUID id) {
        sessionService.revoke(currentUser.requireUserId(), id);
    }

    @PostMapping("/sessions/revoke-others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endOtherSessions(@RequestHeader("Authorization") String authorization) {
        UUID current = sessionIdOf(authorization);
        if (current == null) {
            sessionService.revokeAll(currentUser.requireUserId());
        } else {
            sessionService.revokeOthers(currentUser.requireUserId(), current);
        }
    }

    private UUID sessionIdOf(String authorization) {
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return jwtService.extractSessionId(token).orElse(null);
    }
}
