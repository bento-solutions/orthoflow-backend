package com.orthoflow.prescription.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.Line;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The reference prescriptions (V60): national data, the same for every clinic, so read
 * with plain JDBC and outside the tenant filter, like the NGAP.
 */
@Component
public class PrescriptionLibrary {

    /** One entry as stored. */
    public record Entry(String code, String name, String category, List<Line> lines, String advice, String warningSigns,
                        String alternative, String sourceUrl) {
    }

    private static final TypeReference<List<Line>> LINES = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PrescriptionLibrary(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Entry> all() {
        return jdbc.query("""
                SELECT code, name, category, lines::text AS lines, advice, warning_signs, alternative, source_url
                FROM prescription_library ORDER BY sort_order
                """, (rs, i) -> new Entry(rs.getString("code"), rs.getString("name"), rs.getString("category"),
                lines(rs.getString("lines")), rs.getString("advice"), rs.getString("warning_signs"),
                rs.getString("alternative"), rs.getString("source_url")));
    }

    public Optional<Entry> find(String code) {
        return all().stream().filter(e -> e.code().equals(code)).findFirst();
    }

    private List<Line> lines(String raw) {
        try {
            return json.readValue(raw, LINES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable prescription library lines", e);
        }
    }
}
