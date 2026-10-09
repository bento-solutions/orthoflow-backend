package com.orthoflow.publicapi.application.service;

import com.orthoflow.common.tenancy.AnonymousRequestClinic;
import com.orthoflow.common.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * A public page ({@code /public/<page>/<token>/...}) works for the clinic whose link
 * the token is. Read with plain SQL, before any database session opens; whether the
 * link is still usable, and for this page, is checked again by the service.
 */
@Component
public class PublicLinkRequestClinic implements AnonymousRequestClinic {

    private final JdbcTemplate jdbc;

    public PublicLinkRequestClinic(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> clinicFor(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!path.startsWith("/public/")) {
            return Optional.empty();
        }
        String[] parts = path.split("/");
        // "", "public", page, token, ...
        if (parts.length < 4 || parts[3].length() < 20 || parts[3].length() > 100) {
            return Optional.of(TenantContext.NONE);
        }
        return Optional.of(jdbc.query("SELECT practice_id FROM public_links WHERE token_hash = ?",
                        (rs, i) -> rs.getObject(1, UUID.class), PublicLinkService.hash(parts[3]))
                .stream().findFirst().orElse(TenantContext.NONE));
    }
}
