package com.orthoflow.patient.application.service;

import com.orthoflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moves everything that points at one patient onto another. The list of tables
 * is not written down here: it is read from the database's own foreign keys to
 * {@code patients}, so a table added next year is carried across without anyone
 * remembering to edit a merge routine.
 *
 * <p>Two kinds of table cannot be moved blindly because a patient may hold only
 * one row there: the dental chart (one per patient, and it owns the tooth data)
 * and per-channel consent. Consent keeps the surviving patient's answer; the
 * chart is the one real choice, and the caller must make it when both patients
 * have one — losing a chart silently is exactly the mistake a merge must not make.
 */
@Component
@RequiredArgsConstructor
public class PatientRecordMover {

    public enum ChartChoice { SOURCE, TARGET }

    private static final String DENTAL_CHARTS = "dental_charts";
    private static final String CONSENT = "patient_channel_consent";

    private record Reference(String table, String column) {
    }

    private final JdbcTemplate jdbc;

    /** How many rows the source has in each table that points at a patient. */
    public Map<String, Integer> preview(UUID source) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Reference ref : references()) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM " + ref.table() + " WHERE " + ref.column() + " = ?", Integer.class, source);
            if (n != null && n > 0) {
                counts.put(ref.table(), n);
            }
        }
        return counts;
    }

    public boolean bothHaveDentalCharts(UUID source, UUID target) {
        return hasChart(source) && hasChart(target);
    }

    /** Moves the source's records to the target and returns how many rows moved per table. */
    public Map<String, Integer> move(UUID source, UUID target, ChartChoice chartChoice) {
        if (source.equals(target)) {
            throw new ValidationException("A patient cannot be merged into themselves");
        }
        if (bothHaveDentalCharts(source, target)) {
            if (chartChoice == null) {
                throw new ValidationException("Both patients have a dental chart; say which one to keep (the other is discarded)");
            }
            // The discarded chart goes with its tooth states and findings (they cascade from it).
            jdbc.update("DELETE FROM " + DENTAL_CHARTS + " WHERE patient_id = ?", chartChoice == ChartChoice.SOURCE ? target : source);
        }
        // Consent: where both have answered for a channel, the surviving patient's answer stands.
        jdbc.update("DELETE FROM " + CONSENT + " s WHERE s.patient_id = ? AND EXISTS "
                + "(SELECT 1 FROM " + CONSENT + " t WHERE t.patient_id = ? AND t.channel = s.channel)", source, target);

        Map<String, Integer> moved = new LinkedHashMap<>();
        for (Reference ref : references()) {
            int n = jdbc.update("UPDATE " + ref.table() + " SET " + ref.column() + " = ? WHERE " + ref.column() + " = ?", target, source);
            if (n > 0) {
                moved.put(ref.table(), n);
            }
        }
        return moved;
    }

    private boolean hasChart(UUID patientId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + DENTAL_CHARTS + " WHERE patient_id = ?", Integer.class, patientId);
        return n != null && n > 0;
    }

    /** Every single-column foreign key that references patients(id), excluding the self-reference used to record a merge. */
    private List<Reference> references() {
        return jdbc.query("""
                SELECT c.conrelid::regclass::text AS tbl, a.attname AS col
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.confrelid = 'patients'::regclass AND array_length(c.conkey, 1) = 1
                  AND NOT (c.conrelid = 'patients'::regclass)
                ORDER BY 1, 2
                """, (rs, i) -> new Reference(rs.getString("tbl"), rs.getString("col")));
    }
}
