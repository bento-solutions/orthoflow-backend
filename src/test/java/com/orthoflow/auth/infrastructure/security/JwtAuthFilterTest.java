package com.orthoflow.auth.infrastructure.security;

import com.orthoflow.auth.domain.model.User;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.auth.domain.repository.UserRepository;
import com.orthoflow.common.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The behaviour AUDIT-2026-09 H1 added: a signed, unexpired token is not enough
 * — the filter also has to see a live account whose session has not been
 * invalidated, and it must trust the <em>row's</em> role, not the token's.
 */
class JwtAuthFilterTest {

    private JwtService jwtService;
    private UserRepository userRepository;
    private JwtAuthFilter filter;
    private FilterChain chain;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userRepository = mock(UserRepository.class);
        filter = new JwtAuthFilter(jwtService, userRepository);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();

        when(jwtService.isTokenValid(any())).thenReturn(true);
        when(jwtService.extractUserId(any())).thenReturn(userId);
        when(jwtService.extractIssuedAt(any())).thenReturn(Instant.now());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User user(UserRole role, boolean active, OffsetDateTime sessionsValidAfter) {
        return User.builder()
                .id(userId).email("doc@clinic.ma").role(role).active(active)
                .sessionsValidAfter(sessionsValidAfter)
                .passwordHash("x").firstName("D").lastName("R")
                .build();
    }

    private Authentication runFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer any.token.here");
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void activeUser_isAuthenticatedWithTheRowRole() throws Exception {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(UserRole.DOCTOR, true, null)));

        Authentication auth = runFilter();

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_DOCTOR");
        assertThat(((AuthenticatedUser) auth.getPrincipal()).id()).isEqualTo(userId);
    }

    @Test
    void inactiveUser_isNotAuthenticated() throws Exception {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(UserRole.DOCTOR, false, null)));

        assertThat(runFilter()).isNull();
    }

    @Test
    void unknownUser_isNotAuthenticated() throws Exception {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(runFilter()).isNull();
    }

    @Test
    void tokenIssuedBeforeTheInvalidationCutoff_isRejected() throws Exception {
        when(jwtService.extractIssuedAt(any())).thenReturn(Instant.now().minus(1, ChronoUnit.HOURS));
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user(UserRole.DOCTOR, true, OffsetDateTime.now().minusMinutes(5))));

        assertThat(runFilter()).isNull();
    }

    @Test
    void tokenIssuedAfterTheInvalidationCutoff_isAccepted() throws Exception {
        when(jwtService.extractIssuedAt(any())).thenReturn(Instant.now());
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user(UserRole.ADMIN, true, OffsetDateTime.now().minusMinutes(5))));

        Authentication auth = runFilter();

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void invalidSignature_isNotAuthenticated() throws Exception {
        when(jwtService.isTokenValid(any())).thenReturn(false);

        assertThat(runFilter()).isNull();
    }
}
