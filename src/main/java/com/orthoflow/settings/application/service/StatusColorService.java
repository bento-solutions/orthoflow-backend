package com.orthoflow.settings.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** The colour of each appointment status on the agenda, configurable per clinic over sensible defaults. */
@Service
@RequiredArgsConstructor
public class StatusColorService {

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern KEY = Pattern.compile("^[A-Z_]{2,20}$");

    static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("SCHEDULED", "#3b82f6");
        DEFAULTS.put("CONFIRMED", "#0ea5e9");
        DEFAULTS.put("LATE", "#f59e0b");
        DEFAULTS.put("ARRIVED", "#8b5cf6");
        DEFAULTS.put("IN_CHAIR", "#16a34a");
        DEFAULTS.put("COMPLETED", "#64748b");
        DEFAULTS.put("CANCELLED", "#ef4444");
        DEFAULTS.put("NO_SHOW", "#b91c1c");
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Map<String, String> get(UUID practiceId) {
        Map<String, String> colors = new LinkedHashMap<>(DEFAULTS);
        String json = jdbc.query("SELECT status_colors::text FROM practice_settings WHERE practice_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, practiceId);
        if (json != null) {
            try {
                Map<String, String> stored = objectMapper.readValue(json, new TypeReference<>() {
                });
                stored.forEach((k, v) -> {
                    if (DEFAULTS.containsKey(k) && HEX.matcher(v).matches()) {
                        colors.put(k, v);
                    }
                });
            } catch (Exception ignored) {
                // A corrupt value falls back to the defaults rather than breaking the agenda.
            }
        }
        return colors;
    }

    @Transactional
    public Map<String, String> put(UUID practiceId, Map<String, String> colors) {
        colors.forEach((k, v) -> {
            if (!KEY.matcher(k).matches() || !DEFAULTS.containsKey(k)) {
                throw new ValidationException("Unknown status '" + k + "'");
            }
            if (v == null || !HEX.matcher(v).matches()) {
                throw new ValidationException("'" + v + "' is not a #rrggbb colour");
            }
        });
        try {
            jdbc.update("UPDATE practice_settings SET status_colors = CAST(? AS jsonb), updated_at = NOW() WHERE practice_id = ?",
                    objectMapper.writeValueAsString(colors), practiceId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ValidationException("Colours are not valid");
        }
        return get(practiceId);
    }
}
