package com.orthoflow.booking.application.service;

import com.orthoflow.booking.infrastructure.BookingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the configured doctor account ({@code orthoflow.booking.doctor-email})
 * to their clinic and practitioner record without requiring an authenticated session.
 */
@Component
@RequiredArgsConstructor
public class PublicBookingTargetResolver {

    public record Target(UUID practiceId, UUID practitionerId, String practitionerName) {
    }

    private final JdbcTemplate jdbc;
    private final BookingProperties properties;

    public Optional<Target> resolve() {
        String email = properties.getDoctorEmail();
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        List<Target> rows = jdbc.query("""
                SELECT u.practice_id, p.id AS practitioner_id,
                       COALESCE(p.display_name, trim(concat(u.first_name, ' ', u.last_name))) AS display_name
                FROM users u
                LEFT JOIN practitioners p ON p.user_id = u.id AND p.active = TRUE
                WHERE lower(u.email) = lower(?) AND u.active = TRUE
                LIMIT 1
                """, (rs, i) -> new Target(
                rs.getObject("practice_id", UUID.class),
                rs.getObject("practitioner_id", UUID.class),
                rs.getString("display_name")), email.trim());
        return rows.stream().findFirst();
    }
}
