package com.orthoflow.common.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs work as a given clinic, or across all of them, for the code paths that have
 * no signed-in user to take the clinic from (ADR 0007).
 *
 * <p>Hibernate fixes a session's clinic when it opens, so switching clinic under a
 * session that is already open for another one would silently keep the old one.
 * That is refused with an {@link IllegalStateException}: switch before the session
 * opens (in a filter, or at the top of a job), not inside a transaction.
 */
@Component
public class Tenancy {

    private final EntityManagerFactory entityManagerFactory;

    public Tenancy(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    public <T> T callAs(UUID practiceId, Supplier<T> work) {
        Objects.requireNonNull(practiceId, "practiceId");
        refuseOpenSessionForAnotherClinic(practiceId);
        UUID previous = TenantContext.explicit();
        TenantContext.set(practiceId);
        try {
            return work.get();
        } finally {
            TenantContext.set(previous);
        }
    }

    public void runAs(UUID practiceId, Runnable work) {
        callAs(practiceId, () -> {
            work.run();
            return null;
        });
    }

    /** For work that scopes itself per clinic: the tenant filter is off. */
    public <T> T callAcrossClinics(Supplier<T> work) {
        return callAs(TenantContext.ALL_CLINICS, work);
    }

    public void runAcrossClinics(Runnable work) {
        runAs(TenantContext.ALL_CLINICS, work);
    }

    private void refuseOpenSessionForAnotherClinic(UUID practiceId) {
        Object holder = TransactionSynchronizationManager.getResource(entityManagerFactory);
        if (holder instanceof EntityManagerHolder h && h.getEntityManager().isOpen()) {
            Object open = h.getEntityManager().unwrap(Session.class).getTenantIdentifierValue();
            if (!practiceId.equals(open)) {
                throw new IllegalStateException("A database session for clinic " + open
                        + " is already open; switch clinic before it opens, not inside it");
            }
        }
    }
}
