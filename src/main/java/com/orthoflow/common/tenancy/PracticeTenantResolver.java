package com.orthoflow.common.tenancy;

import com.orthoflow.common.security.AuthenticatedUser;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.jpa.boot.internal.EntityManagerFactoryBuilderImpl;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells Hibernate which clinic a new session belongs to. Every entity with a
 * {@code @TenantId practiceId} is then filtered to that clinic in every query and
 * load, and stamped with it on insert, so a repository call that forgets the clinic
 * fails closed instead of reading another clinic's rows (ADR 0007).
 *
 * <p>Order: a clinic set explicitly through {@link Tenancy}; else the signed-in
 * user's clinic; else {@link TenantContext#NONE}, which matches nothing.
 * {@link TenantContext#ALL_CLINICS} is Hibernate's "root" tenant: no filter.
 *
 * <p>The clinic is fixed when the session opens. With open-session-in-view that is
 * once per request, after the security filters have run; {@link Tenancy} guards
 * against switching under an open session.
 */
@Component
public class PracticeTenantResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        UUID explicit = TenantContext.explicit();
        if (explicit != null) {
            return explicit;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user && user.practiceId() != null) {
            return user.practiceId();
        }
        return TenantContext.NONE;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return TenantContext.ALL_CLINICS.equals(tenantId);
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
        hibernateProperties.put(EntityManagerFactoryBuilderImpl.INTEGRATOR_PROVIDER,
                (IntegratorProvider) () -> List.of(new TenantLoadGuard()));
    }
}
