package com.orthoflow.common.tenancy;

import java.util.UUID;

/**
 * The clinic a unit of work runs as, when it is not simply "the signed-in user's clinic":
 * a public page reached by a clinic's token, a webhook, a background job (ADR 0007).
 * {@link PracticeTenantResolver} reads it before falling back to the signed-in user.
 *
 * <p>Set it only through {@link Tenancy}, which also refuses to switch clinic under a
 * database session that is already open for another one.
 */
public final class TenantContext {

    /**
     * Matches no clinic. What a request with neither a signed-in user nor an explicit
     * clinic runs as, so a code path nobody scoped reads nothing instead of everything.
     */
    public static final UUID NONE = new UUID(0L, 0L);

    /**
     * Every clinic: Hibernate's tenant filter is off. Only for work that already scopes
     * itself per clinic (the scheduled jobs, the sign-in lookups), never for a request.
     */
    public static final UUID ALL_CLINICS = new UUID(-1L, -1L);

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** The clinic set explicitly for this thread, or null. */
    public static UUID explicit() {
        return CURRENT.get();
    }

    static void set(UUID practiceId) {
        if (practiceId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(practiceId);
        }
    }
}
