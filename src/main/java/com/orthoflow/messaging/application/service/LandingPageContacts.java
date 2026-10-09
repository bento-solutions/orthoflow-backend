package com.orthoflow.messaging.application.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The numbers that reached a clinic through its landing page (V58): a booking made
 * there, a WhatsApp conversation opened from its click-to-chat link, or a patient
 * created from either. On a WhatsApp number shared with the bento CRM, these are the
 * only senders OrthoFlow keeps messages from.
 */
@Component
public class LandingPageContacts {

    public enum Source { BOOKING, WHATSAPP_LINK }

    private final JdbcTemplate jdbc;

    public LandingPageContacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The last nine digits of a number, however it was typed; null when it has fewer. */
    public static String key(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() < 9 ? null : digits.substring(digits.length() - 9);
    }

    /** Remembers a number; the first source is kept. */
    public void record(UUID practiceId, String phone, Source source) {
        String key = key(phone);
        if (key == null) {
            return;
        }
        jdbc.update("INSERT INTO landing_page_contacts (practice_id, phone_key, source) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                practiceId, key, source.name());
    }

    public boolean isKnown(UUID practiceId, String phone) {
        String key = key(phone);
        if (key == null) {
            return false;
        }
        Boolean known = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM landing_page_contacts WHERE practice_id = ? AND phone_key = ?)
                    OR EXISTS (SELECT 1 FROM patients WHERE practice_id = ? AND acquisition_channel = 'LANDING_PAGE'
                                  AND deleted_at IS NULL AND phone_digits(phone) = ?)
                """, Boolean.class, practiceId, key, practiceId, key);
        return Boolean.TRUE.equals(known);
    }
}
