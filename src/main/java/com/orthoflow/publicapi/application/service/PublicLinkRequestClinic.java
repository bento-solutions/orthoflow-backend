package com.orthoflow.publicapi.application.service;

import com.orthoflow.booking.application.service.PublicBookingTargetResolver;
import com.orthoflow.common.tenancy.AnonymousRequestClinic;
import com.orthoflow.common.tenancy.Practices;
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
 *
 * <p>Direct doctor booking endpoints ({@code /public/booking/...}) and public config
 * ({@code /public/config}) resolve the clinic from {@link PublicBookingTargetResolver}.
 */
@Component
public class PublicLinkRequestClinic implements AnonymousRequestClinic {

    private final JdbcTemplate jdbc;
    private final PublicBookingTargetResolver targetResolver;

    public PublicLinkRequestClinic(JdbcTemplate jdbc, PublicBookingTargetResolver targetResolver) {
        this.jdbc = jdbc;
        this.targetResolver = targetResolver;
    }

    @Override
    public Optional<UUID> clinicFor(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!path.startsWith("/public/")) {
            return Optional.empty();
        }
        if (path.equals("/public/config") || path.equals("/public/booking") || path.startsWith("/public/booking/")) {
            return Optional.of(targetResolver.resolve()
                    .map(PublicBookingTargetResolver.Target::practiceId)
                    .or(() -> defaultPracticeIfPresent())
                    .orElse(TenantContext.NONE));
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

    private Optional<UUID> defaultPracticeIfPresent() {
        return jdbc.query("SELECT id FROM practices WHERE id = ? AND active = TRUE",
                (rs, i) -> rs.getObject(1, UUID.class), Practices.DEFAULT_ID).stream().findFirst();
    }
}
