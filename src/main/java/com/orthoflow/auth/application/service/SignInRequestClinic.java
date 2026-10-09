package com.orthoflow.auth.application.service;

import com.orthoflow.common.tenancy.AnonymousRequestClinic;
import com.orthoflow.common.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Signing in, the first account and password resets happen before anyone's clinic
 * is known: the account is found by e-mail or token across every clinic, and what
 * these paths write is stamped with that account's own clinic explicitly.
 */
@Component
public class SignInRequestClinic implements AnonymousRequestClinic {

    private static final Set<String> PATHS = Set.of("/auth/login", "/auth/register", "/auth/forgot-password", "/auth/reset-password");

    @Override
    public Optional<UUID> clinicFor(HttpServletRequest request) {
        return PATHS.contains(request.getServletPath()) ? Optional.of(TenantContext.ALL_CLINICS) : Optional.empty();
    }
}
