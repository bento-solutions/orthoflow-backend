package com.orthoflow.compliance.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.compliance.application.service.PatientDataRegistry.Reference;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A raw copy of everything the database holds about one patient, table by table: the tables that point
 * at them (all of them, as {@link PatientDataRegistry} knows them) and the tables that hang off those
 * (an invoice's lines, a chart's teeth, a plan's instalments), found by following the foreign keys down.
 * Nothing here is written per table, so a table added next year is in the export the day it exists;
 * the typed sections of the export are a readable view, this is the complete one.
 *
 * <p>Rows are returned as the database stores them (one JSON object per row, column names as they are).
 * Stored files are listed, not embedded: their content is fetched through the files endpoint.
 */
@Component
@RequiredArgsConstructor
public class PatientRecordsExporter {

    /** Tables that refer to a patient's records but are the clinic's own books, not the patient's data. */
    private static final Set<String> CLINICS_OWN = Set.of("expenses", "retrocession_statement_lines");

    private record Edge(String child, String childColumn, String parent) {
    }

    private final JdbcTemplate jdbc;
    private final PatientDataRegistry registry;
    private final UnlinkedCopies unlinked;
    private final ObjectMapper objectMapper;

    public Map<String, List<Map<String, Object>>> records(UUID patientId) {
        Map<String, String> predicates = new LinkedHashMap<>();
        for (Reference ref : registry.references()) {
            predicates.merge(ref.table(), ref.column() + " = :p", (a, b) -> a + " OR " + b);
        }
        predicates.replaceAll((table, predicate) -> "(" + predicate + ")");

        List<Edge> edges = jdbc.query("""
                SELECT c.conrelid::regclass::text AS child, a.attname AS child_col, c.confrelid::regclass::text AS parent
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                JOIN pg_attribute pa ON pa.attrelid = c.confrelid AND pa.attnum = c.confkey[1]
                WHERE c.contype = 'f' AND array_length(c.conkey, 1) = 1 AND pa.attname = 'id'
                  AND c.confrelid <> 'patients'::regclass AND c.conrelid <> 'patients'::regclass
                """, (rs, i) -> new Edge(rs.getString("child"), rs.getString("child_col"), rs.getString("parent")));

        Deque<String> queue = new ArrayDeque<>(predicates.keySet());
        while (!queue.isEmpty()) {
            String parent = queue.poll();
            for (Edge edge : edges) {
                if (edge.parent().equals(parent) && !predicates.containsKey(edge.child()) && !CLINICS_OWN.contains(edge.child())) {
                    predicates.put(edge.child(), edge.childColumn() + " IN (SELECT id FROM " + parent + " WHERE " + predicates.get(parent) + ")");
                    queue.add(edge.child());
                }
            }
        }

        NamedParameterJdbcTemplate named = unlinked.named();
        MapSqlParameterSource p = new MapSqlParameterSource("p", patientId);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        predicates.keySet().stream().sorted().forEach(table -> {
            List<Map<String, Object>> rows = rows(named, "SELECT to_jsonb(t)::text FROM " + table + " t WHERE " + predicates.get(table), p);
            if (!rows.isEmpty()) {
                out.put(table, rows);
            }
        });
        return out;
    }

    /** Stored files (photos, scans, documents) that belong to the patient: who and what, never the storage path. */
    public List<Map<String, Object>> files(UUID patientId) {
        return rows(unlinked.named(), "SELECT (to_jsonb(f) - 'storage_key')::text FROM files f WHERE owner_id = :p AND deleted_at IS NULL ORDER BY created_at",
                new MapSqlParameterSource("p", patientId));
    }

    /** Requests, forms and messages from the same person that were never linked to the record (see {@link UnlinkedCopies}). */
    public Map<String, List<Map<String, Object>>> unlinkedCopies(UUID patientId) {
        MapSqlParameterSource params = unlinked.parametersFor(patientId);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (UnlinkedCopies.Source source : UnlinkedCopies.SOURCES) {
            List<Map<String, Object>> rows = rows(unlinked.named(), "SELECT to_jsonb(t)::text FROM " + source.table() + " t WHERE " + source.condition(), params);
            if (!rows.isEmpty()) {
                out.put(source.table(), rows);
            }
        }
        return out;
    }

    private List<Map<String, Object>> rows(NamedParameterJdbcTemplate named, String sql, MapSqlParameterSource params) {
        return named.queryForList(sql, params, String.class).stream().map(json -> {
            try {
                return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException("Could not read a row for the export", e);
            }
        }).toList();
    }
}
