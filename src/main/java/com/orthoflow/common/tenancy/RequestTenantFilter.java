package com.orthoflow.common.tenancy;

import com.orthoflow.common.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Settles the clinic of a request that has no signed-in user, before the request's
 * database session opens (open-session-in-view opens it in the DispatcherServlet,
 * after every servlet filter). A signed-in request needs nothing here: the resolver
 * takes the user's clinic. Anything no {@link AnonymousRequestClinic} claims runs as
 * {@link TenantContext#NONE} and reads nothing (ADR 0007).
 *
 * <p>Ordered after Spring Security (-100), so the signed-in user is already known.
 */
@Component
@Order(0)
public class RequestTenantFilter extends OncePerRequestFilter {

    private final List<AnonymousRequestClinic> resolvers;

    public RequestTenantFilter(List<AnonymousRequestClinic> resolvers) {
        this.resolvers = resolvers;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser) {
            chain.doFilter(request, response);
            return;
        }
        UUID clinic = TenantContext.NONE;
        for (AnonymousRequestClinic resolver : resolvers) {
            Optional<UUID> found = resolver.clinicFor(request);
            if (found.isPresent()) {
                clinic = found.get();
                break;
            }
        }
        UUID previous = TenantContext.explicit();
        TenantContext.set(clinic);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.set(previous);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
