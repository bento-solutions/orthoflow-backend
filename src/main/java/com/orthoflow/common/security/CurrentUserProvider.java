package com.orthoflow.common.security;

import com.orthoflow.common.exception.UnauthorizedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single point of truth for "who is making this request". Every service that
 * previously stamped a random UUID as the actor of a mutation should read it
 * from here instead — it comes from the verified JWT, not client input.
 */
@Component
public class CurrentUserProvider {

    public UUID requireUserId() {
        return require().id();
    }

    /** The signed-in user's clinic — what every new row's {@code practice_id} is stamped with. */
    public UUID requirePracticeId() {
        return require().practiceId();
    }

    public String requireRole() {
        return require().role();
    }

    public AuthenticatedUser require() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof AuthenticatedUser user)) {
            throw new UnauthorizedException("No authenticated user in context");
        }
        return user;
    }

    /** For checks that depend on the row being touched: 403 when the signed-in user lacks the permission. */
    public void requireAuthority(String authority) {
        if (!hasAuthority(authority)) {
            throw new org.springframework.security.access.AccessDeniedException("Missing permission " + authority);
        }
    }

    public boolean hasAuthority(String authority) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority));
    }
}
