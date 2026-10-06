package com.orthoflow.retrocession.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.team.application.service.PractitionerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * What a collaborator's pay is worth to a colleague is nothing they should see.
 * Anyone who may manage retrocessions sees every practitioner; anyone who may only
 * view them sees their own and nobody else's.
 */
@Component
@RequiredArgsConstructor
public class RetrocessionAccess {

    private final CurrentUserProvider currentUser;
    private final PractitionerService practitioners;

    /** The practitioner the caller is confined to, or null when they may see all. */
    public UUID scope() {
        if (currentUser.hasAuthority(Permission.RETROCESSION_MANAGE.name())) {
            return null;
        }
        return practitioners.findIdByUser(currentUser.requireUserId())
                .orElseThrow(() -> new AccessDeniedException("Retrocessions are only visible to the practitioner they belong to"));
    }

    /** The practitioner a query should be limited to: the one asked for, forced to the caller's own when confined. */
    public UUID narrow(UUID requested) {
        UUID scope = scope();
        if (scope == null) {
            return requested;
        }
        if (requested != null && !requested.equals(scope)) {
            throw new AccessDeniedException("You can only see your own retrocessions");
        }
        return scope;
    }

    public void check(UUID practitionerId) {
        narrow(practitionerId);
    }
}
