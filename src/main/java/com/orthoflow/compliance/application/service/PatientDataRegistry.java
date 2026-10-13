package com.orthoflow.compliance.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every table that holds something about a patient, and what a data-subject request does with it.
 *
 * <p>The tables are not listed here, the database lists them: every single-column foreign key to
 * {@code patients(id)} is a table of patient data. What is written down is the decision for each,
 * and a test fails the build when a table appears that has none ({@link #unclassified()}), so a
 * module added next year cannot quietly hold personal data that the export forgets and the erasure
 * leaves behind. At run time an unclassified table stops an erasure rather than letting it
 * pretend to be complete.
 */
@Component
@RequiredArgsConstructor
public class PatientDataRegistry {

    /** What an erasure or an anonymisation does to the rows of a table that point at the patient. */
    public enum Rule {
        /** The rows are the patient's own data: removed by erasure, removed by anonymisation. */
        DELETE,
        /** The rows outlive the patient with the link and the free text taken out (an audit trail, a traceability record). */
        SCRUB,
        /** The law makes the clinic keep these: they block an erasure, and anonymisation keeps them with their free text blanked. */
        RETAIN
    }

    /** One foreign key from a table to {@code patients(id)}. */
    public record Reference(String table, String column) {
    }

    private static final Map<String, Rule> RULES = Map.ofEntries(
            // Clinical and personal data.
            Map.entry("appointments", Rule.DELETE),
            Map.entry("patient_treatments", Rule.DELETE),
            Map.entry("dental_charts", Rule.DELETE),
            Map.entry("tooth_state_events", Rule.DELETE),
            Map.entry("clinical_notes", Rule.DELETE),
            Map.entry("patient_allergies", Rule.DELETE),
            Map.entry("patient_medical_history", Rule.DELETE),
            Map.entry("periodontal_assessments", Rule.DELETE),
            Map.entry("perio_charts", Rule.DELETE),
            Map.entry("clinical_photo_series", Rule.DELETE),
            Map.entry("voice_sessions", Rule.DELETE),
            Map.entry("consultations", Rule.DELETE),
            Map.entry("prescriptions", Rule.DELETE),
            Map.entry("insurance_forms", Rule.DELETE),
            Map.entry("lab_orders", Rule.DELETE),
            Map.entry("tasks", Rule.DELETE),
            Map.entry("waiting_list_entries", Rule.DELETE),
            Map.entry("satisfaction_surveys", Rule.DELETE),
            // Contact details and what was said to or by the person.
            Map.entry("patient_phones", Rule.DELETE),
            Map.entry("patient_channel_consent", Rule.DELETE),
            Map.entry("message_outbox", Rule.DELETE),
            Map.entry("message_events", Rule.DELETE),
            Map.entry("booking_requests", Rule.DELETE),
            Map.entry("pending_patients", Rule.DELETE),
            // Trails that outlive the person, with the person taken out of them.
            Map.entry("voice_command_audit", Rule.SCRUB),
            Map.entry("sterilization_events", Rule.SCRUB),
            // Financial records the law makes the clinic keep.
            Map.entry("invoices", Rule.RETAIN),
            Map.entry("receipts", Rule.RETAIN),
            Map.entry("payment_plans", Rule.RETAIN),
            Map.entry("cheques", Rule.RETAIN),
            Map.entry("tax_documents", Rule.RETAIN),
            Map.entry("sales_orders", Rule.RETAIN),
            Map.entry("treatment_invoices", Rule.RETAIN));

    /** Emptied before the rest: other tables point at consultations and tasks without a cascade. */
    private static final List<String> DELETE_FIRST = List.of("prescriptions", "insurance_forms");

    private final JdbcTemplate jdbc;

    /** Every foreign key to {@code patients(id)}, except the self-reference that records a merge. */
    public List<Reference> references() {
        return jdbc.query("""
                SELECT c.conrelid::regclass::text AS tbl, a.attname AS col
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.confrelid = 'patients'::regclass AND array_length(c.conkey, 1) = 1
                  AND NOT (c.conrelid = 'patients'::regclass)
                ORDER BY 1, 2
                """, (rs, i) -> new Reference(rs.getString("tbl"), rs.getString("col")));
    }

    public Rule ruleFor(String table) {
        return RULES.get(table);
    }

    /** The tables that point at a patient and have no decision yet: empty, or the build and every erasure fail. */
    public List<String> unclassified() {
        return references().stream().map(Reference::table).filter(t -> !RULES.containsKey(t)).distinct().toList();
    }

    /** The foreign keys of tables with this rule, in the order their rows must be removed. */
    public List<Reference> referencesWith(Rule rule) {
        List<Reference> all = references().stream().filter(r -> RULES.get(r.table()) == rule).toList();
        if (rule != Rule.DELETE) {
            return all;
        }
        List<Reference> ordered = new ArrayList<>();
        for (String first : DELETE_FIRST) {
            all.stream().filter(r -> r.table().equals(first)).forEach(ordered::add);
        }
        all.stream().filter(r -> !DELETE_FIRST.contains(r.table())).forEach(ordered::add);
        return ordered;
    }
}
