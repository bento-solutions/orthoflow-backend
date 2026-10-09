package com.orthoflow.common.tenancy;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The clinic the current unit of work runs as: the one Hibernate scopes the session
 * to. For code written before services took the clinic as an argument.
 */
@Component
public class CurrentPractice {

    private final PracticeTenantResolver resolver;

    public CurrentPractice(PracticeTenantResolver resolver) {
        this.resolver = resolver;
    }

    /** Refuses when no single clinic is in scope (an anonymous request, a cross-clinic job). */
    public UUID require() {
        UUID practice = resolver.resolveCurrentTenantIdentifier();
        if (TenantContext.NONE.equals(practice) || TenantContext.ALL_CLINICS.equals(practice)) {
            throw new IllegalStateException("No clinic is in scope for this work");
        }
        return practice;
    }
}
