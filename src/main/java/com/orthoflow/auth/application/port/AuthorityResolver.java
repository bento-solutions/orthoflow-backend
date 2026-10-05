package com.orthoflow.auth.application.port;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.domain.model.UserRole;

import java.util.Set;
import java.util.UUID;

/**
 * What a role may do in a clinic right now. {@code JwtAuthFilter} calls it on
 * every request, so a permission an admin just removed stops working on the
 * next call — the same promise the filter already makes for deactivation and
 * demotion.
 */
public interface AuthorityResolver {

    Set<Permission> permissionsFor(UUID practiceId, UserRole role);
}
