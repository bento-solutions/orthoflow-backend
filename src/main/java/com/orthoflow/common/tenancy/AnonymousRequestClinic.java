package com.orthoflow.common.tenancy;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Names the clinic a request with no signed-in user works for: a public page reached
 * by a clinic's link, a webhook, the sign-in endpoints. Asked by
 * {@link RequestTenantFilter} before the request's database session opens.
 */
public interface AnonymousRequestClinic {

    /**
     * Empty when this request is not one this resolver knows. A known request whose
     * clinic cannot be found (an unknown token) answers {@link TenantContext#NONE}.
     */
    Optional<UUID> clinicFor(HttpServletRequest request);
}
