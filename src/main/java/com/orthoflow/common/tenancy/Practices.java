package com.orthoflow.common.tenancy;

import java.util.UUID;

/**
 * The clinic every row belongs to (ADR 0007). Deployments are still one clinic
 * each, but every aggregate root carries a {@code practice_id} so a shared,
 * multi-clinic system does not have to retrofit one.
 *
 * <p>{@link #DEFAULT_ID} is the clinic that existed before the column did; the
 * schema defaults to it, and a signed-in user's own clinic comes from the
 * {@code users.practice_id} column through
 * {@link com.orthoflow.common.security.CurrentUserProvider#requirePracticeId()}.
 */
public final class Practices {

    public static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Practices() {
    }
}
