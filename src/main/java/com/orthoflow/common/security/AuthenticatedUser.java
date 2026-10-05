package com.orthoflow.common.security;

import com.orthoflow.common.tenancy.Practices;

import java.util.UUID;

/**
 * The authenticated principal attached to the SecurityContext by JwtAuthFilter.
 * Every controller/service that needs "who did this" (audit actors, ownership
 * checks) reads it from the SecurityContext rather than trusting a client-supplied id.
 *
 * <p>{@code practiceId} is the signed-in user's clinic, read from their row on
 * every request — never from the token or the client.
 */
public record AuthenticatedUser(UUID id, String email, String role, UUID practiceId) {

    /** For callers that predate tenancy: the default clinic. */
    public AuthenticatedUser(UUID id, String email, String role) {
        this(id, email, role, Practices.DEFAULT_ID);
    }
}
