package com.orthoflow.compliance.application.service;

import com.orthoflow.messaging.application.service.LandingPageContacts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Copies of a person's details that nothing links to their patient record: a booking request or a
 * registration form from them that never became the patient, a WhatsApp message from their number
 * before they were registered, the landing-page contact for it. The export must show them to the person
 * and the erasure must remove them, so both use the same definition of "theirs".
 *
 * <p>A form is theirs when the NAME matches and so does the phone or the e-mail. A number alone is
 * not enough (a number is often a family's), and a name alone is not either. Messages and landing-page
 * contacts hold only a number, so the number is what counts there.
 */
@Component
public class UnlinkedCopies {

    /** A table and the condition (named parameters: practice, first, last, email, keys) that selects the person's rows in it. */
    public record Source(String table, String condition) {
    }

    private static final String FORM = "practice_id = :practice AND lower(first_name) = lower(:first) AND lower(last_name) = lower(:last)"
            + " AND (phone_digits(phone) IN (:keys) OR lower(email) = lower(:email))";

    public static final List<Source> SOURCES = List.of(
            new Source("booking_requests", FORM),
            new Source("pending_patients", FORM),
            new Source("message_events", "practice_id = :practice AND phone_digits(from_phone) IN (:keys)"),
            new Source("landing_page_contacts", "practice_id = :practice AND phone_key IN (:keys)"));

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;

    public UnlinkedCopies(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public NamedParameterJdbcTemplate named() {
        return named;
    }

    /** The parameters of every {@link Source} for this patient: their clinic, name, e-mail and the last nine digits of each number. */
    public MapSqlParameterSource parametersFor(UUID patientId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT practice_id, first_name, last_name, email, phone FROM patients WHERE id = ?", patientId);
        Set<String> keys = new LinkedHashSet<>();
        add(keys, (String) row.get("phone"));
        jdbc.queryForList("SELECT number FROM patient_phones WHERE patient_id = ?", String.class, patientId).forEach(n -> add(keys, n));
        List<String> keyList = new ArrayList<>(keys);
        if (keyList.isEmpty()) {
            keyList.add("none"); // an empty IN () is not valid SQL; no phone key is ever the word "none"
        }
        return new MapSqlParameterSource().addValue("practice", row.get("practice_id")).addValue("first", row.get("first_name"))
                .addValue("last", row.get("last_name")).addValue("email", row.get("email")).addValue("keys", keyList);
    }

    private static void add(Set<String> keys, String phone) {
        String key = LandingPageContacts.key(phone);
        if (key != null) {
            keys.add(key);
        }
    }
}
