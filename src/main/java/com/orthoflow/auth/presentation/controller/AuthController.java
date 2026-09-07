package com.orthoflow.auth.presentation.controller;

import com.orthoflow.auth.application.dto.ForgotPasswordRequest;
import com.orthoflow.auth.application.dto.LoginRequest;
import com.orthoflow.auth.application.dto.LoginResponse;
import com.orthoflow.auth.application.dto.RegisterRequest;
import com.orthoflow.auth.application.dto.ResetPasswordRequest;
import com.orthoflow.auth.application.dto.UserResponse;
import com.orthoflow.auth.application.service.AuthService;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.common.exception.UnauthorizedException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // TRUE everywhere except throwaway dev environments (default is true in
    // application-prod.yml). When true, /auth/register only creates the very
    // first account (bootstrap) — forced to ADMIN below — and every account
    // after that needs an authenticated ADMIN. When false, /auth/register is
    // a fully open, self-service endpoint: only acceptable on a disposable
    // database no real patient data will ever touch.
    @Value("${orthoflow.auth.restrict-registration-to-bootstrap:true}")
    private boolean restrictRegistrationToBootstrap;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Open only to bootstrap the very first account on an empty database, which
     * is always created as ADMIN (the practice owner) regardless of the role in
     * the request — the client does not get to choose the role of the first
     * account. Every account after that requires an authenticated ADMIN.
     *
     * <p>The path itself is {@code permitAll} in SecurityConfig because the
     * bootstrap case has no one to authenticate as; the "is the users table
     * empty" decision can only be made here, not in the path matrix.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        if (restrictRegistrationToBootstrap) {
            boolean bootstrap = authService.noUsersExist();
            if (bootstrap) {
                // First account is the practice owner. Never honour a
                // client-supplied role here — that is the ADMIN-mints-ADMIN
                // hole this whole branch exists to close.
                request.setRole(UserRole.ADMIN);
            } else if (!hasAdminRole()) {
                throw new UnauthorizedException("Only an administrator can create new accounts");
            }
        }
        return authService.register(request);
    }

    /**
     * Always 202/no body, whether or not the email is registered — see
     * AuthService#requestPasswordReset for why the response can't vary.
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request.getEmail());
    }

    @PostMapping("/reset-password")
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }

    private boolean hasAdminRole() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
