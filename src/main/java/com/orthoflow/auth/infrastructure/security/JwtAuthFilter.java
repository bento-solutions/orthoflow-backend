package com.orthoflow.auth.infrastructure.security;

import com.orthoflow.auth.application.port.AuthorityResolver;
import com.orthoflow.auth.application.port.SessionRegistry;
import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.domain.model.User;
import com.orthoflow.auth.domain.repository.UserRepository;
import com.orthoflow.common.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates a request from its {@code Authorization: Bearer} token.
 *
 * <p>The token's signature and expiry are verified by {@link JwtService}, but
 * that alone is not enough: a token is valid for hours, and in that window the
 * account behind it can be deactivated, demoted, or have its password reset.
 * So this filter also loads the {@link User} on every request and refuses the
 * token if:
 * <ul>
 *   <li>the user no longer exists or {@code active} is false, or</li>
 *   <li>the token was issued before {@link User#getSessionsValidAfter()} —
 *       stamped on a password reset, so a stolen token dies with the reset.</li>
 * </ul>
 *
 * <p>Authorities come from the user row's <em>current</em> role, not the role
 * baked into the token, so a demotion (DOCTOR&nbsp;&rarr;&nbsp;ASSISTANT) takes
 * effect on the very next request.
 *
 * <p>A rejected token leaves the SecurityContext empty; the request then meets
 * {@code SecurityConfig}'s {@code denyAll} floor and the entry point returns
 * 401.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuthorityResolver authorityResolver;
    private final SessionRegistry sessionRegistry;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            authenticate(token).ifPresent(authentication ->
                    SecurityContextHolder.getContext().setAuthentication(authentication));
        }

        filterChain.doFilter(request, response);
    }

    private Optional<UsernamePasswordAuthenticationToken> authenticate(String token) {
        if (!jwtService.isTokenValid(token)) {
            return Optional.empty();
        }

        UUID userId;
        Instant issuedAt;
        try {
            userId = jwtService.extractUserId(token);
            issuedAt = jwtService.extractIssuedAt(token);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }

        // A revoked sign-in is refused even though its token is still inside its
        // expiry: "sign out this device" has to mean this device.
        var sessionId = jwtService.extractSessionId(token);
        if (sessionId.isPresent() && !sessionRegistry.isLive(sessionId.get())) {
            return Optional.empty();
        }

        return userRepository.findById(userId)
                .filter(User::isActive)
                .filter(user -> notInvalidated(user, issuedAt))
                .map(user -> {
                    var principal = new AuthenticatedUser(
                            user.getId(), user.getEmail(), user.getRole().name(), user.getPracticeId());
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
                    // Permissions are resolved per request, like the role, so an admin
                    // removing one takes effect on the very next call.
                    for (Permission permission : authorityResolver.permissionsFor(user.getPracticeId(), user.getRole())) {
                        authorities.add(new SimpleGrantedAuthority(permission.name()));
                    }
                    return new UsernamePasswordAuthenticationToken(principal, null, authorities);
                });
    }

    private static boolean notInvalidated(User user, Instant tokenIssuedAt) {
        return user.getSessionsValidAfter() == null
                || !tokenIssuedAt.isBefore(user.getSessionsValidAfter().toInstant());
    }
}
