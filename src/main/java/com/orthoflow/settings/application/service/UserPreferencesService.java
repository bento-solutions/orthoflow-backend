package com.orthoflow.settings.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Per-user interface preferences — default agenda view, visible filters, doctor
 * order, patient-list columns. The JSON is opaque to the server: the frontend
 * owns its shape, so a new preference never needs a migration.
 */
@Service
@RequiredArgsConstructor
public class UserPreferencesService {

    private static final int MAX_BYTES = 20_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID userId) {
        String json = jdbc.query("SELECT prefs::text FROM user_preferences WHERE user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Transactional
    public Map<String, Object> put(UUID userId, Map<String, Object> prefs) {
        String json;
        try {
            json = objectMapper.writeValueAsString(prefs);
        } catch (Exception e) {
            throw new ValidationException("Preferences are not valid JSON");
        }
        if (json.length() > MAX_BYTES) {
            throw new ValidationException("Preferences are too large");
        }
        jdbc.update("""
                INSERT INTO user_preferences (user_id, prefs, updated_at) VALUES (?, CAST(? AS jsonb), NOW())
                ON CONFLICT (user_id) DO UPDATE SET prefs = EXCLUDED.prefs, updated_at = NOW()
                """, userId, json);
        return prefs;
    }
}
