package com.orthoflow.compliance.application.service;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.compliance.application.service.PatientDataRegistry.Reference;
import com.orthoflow.compliance.application.service.PatientDataRegistry.Rule;
import com.orthoflow.patient.application.port.PatientErasureListener;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Removes what the database cascade does not, so that an erasure or an anonymisation leaves
 * nothing that says who the person was or what was done for them (the decisions are in
 * {@link PatientDataRegistry}). It runs inside the erasure's transaction, before the patient row goes,
 * and a failure undoes the whole request.
 *
 * <p>Beyond the rows that point at the patient, copies exist that nothing links to them: a
 * booking request or a registration form from the same person that never became the patient, a
 * WhatsApp message from their number before they were a patient, the landing-page contact. Those
 * are found by name together with the phone or e-mail, never by the phone alone (a number is
 * often a family's).
 */
@Component
@RequiredArgsConstructor
public class PatientDataPurger implements PatientErasureListener {

    private static final Logger log = LoggerFactory.getLogger(PatientDataPurger.class);

    private final JdbcTemplate jdbc;
    private final PatientDataRegistry registry;
    private final CurrentUserProvider currentUser;
    private final UnlinkedCopies copies;

    @Override
    public void onPatientErased(UUID patientId) {
        purge(patientId);
        // Records merged into this patient hold only an identity by now; they cannot outlive the person they became.
        jdbc.update("DELETE FROM patients WHERE merged_into_id = ?", patientId);
    }

    @Override
    public void onPatientAnonymised(UUID patientId) {
        purge(patientId);
        scrubFinancialRecords(patientId);
        wipeIdentity(patientId);
    }

    private void purge(UUID patientId) {
        List<String> unclassified = registry.unclassified();
        if (!unclassified.isEmpty()) {
            // A table that points at a patient has no erasure decision: finishing would only look complete.
            throw new IllegalStateException("Tables hold patient data and have no erasure rule: " + unclassified
                    + " (add them to PatientDataRegistry)");
        }
        UUID practiceId = jdbc.queryForObject("SELECT practice_id FROM patients WHERE id = ?", UUID.class, patientId);

        // What was recorded about the person in the activity feed names them in its diffs.
        jdbc.update("""
                DELETE FROM activity_events WHERE practice_id = ? AND
                    ((entity_type = 'PATIENT' AND entity_id = ?) OR (entity_type = 'APPOINTMENT' AND entity_id IN (SELECT id FROM appointments WHERE patient_id = ?)))
                """, practiceId, patientId, patientId);

        copiesWithNoLink(patientId);

        for (Reference ref : registry.referencesWith(Rule.DELETE)) {
            jdbc.update("DELETE FROM " + ref.table() + " WHERE " + ref.column() + " = ?", patientId);
        }
        // The sterilization trail must keep what was sterilised and when, not for whom.
        jdbc.update("UPDATE sterilization_events SET patient_id = NULL, note = NULL WHERE patient_id = ?", patientId);
        log.info("Patient data purged for patient {}", patientId);
    }

    /** Requests, forms, messages and contacts from this person that no foreign key ties to the patient. */
    private void copiesWithNoLink(UUID patientId) {
        var params = copies.parametersFor(patientId);
        for (UnlinkedCopies.Source source : UnlinkedCopies.SOURCES) {
            copies.named().update("DELETE FROM " + source.table() + " WHERE " + source.condition(), params);
        }
    }

    /**
     * What a retained financial record says in free text may name the person or a relative; its
     * numbers, dates, amounts and status are what the law keeps, and they stay.
     */
    private void scrubFinancialRecords(UUID patientId) {
        jdbc.update("UPDATE invoices SET notes = NULL WHERE patient_id = ?", patientId);
        jdbc.update("UPDATE receipts SET notes = NULL, void_reason = NULL WHERE patient_id = ?", patientId);
        jdbc.update("UPDATE payment_plans SET notes = NULL WHERE patient_id = ?", patientId);
        jdbc.update("UPDATE cheques SET notes = NULL, drawer_name = NULL WHERE patient_id = ?", patientId);
        jdbc.update("UPDATE tax_documents SET notes = NULL WHERE patient_id = ?", patientId);
        jdbc.update("UPDATE sales_orders SET notes = NULL WHERE patient_id = ?", patientId);
        jdbc.update("UPDATE treatment_invoices SET notes = NULL WHERE patient_id = ?", patientId);
        jdbc.update("UPDATE payments SET notes = NULL WHERE invoice_id IN (SELECT id FROM invoices WHERE patient_id = ?) "
                + "OR receipt_id IN (SELECT id FROM receipts WHERE patient_id = ?)", patientId, patientId);
    }

    /** Which {@code patients} columns anonymisation blanks; a test makes every column be in this list or in {@link #KEPT_COLUMNS}. */
    public static final List<String> WIPED_COLUMNS = List.of("first_name", "last_name", "date_of_birth", "gender", "email", "phone", "address", "cin",
            "guardian_name", "guardian_phone", "photo_file_id", "occupation", "referral_source", "insurer_id", "insurance_provider",
            "insurance_number", "insurance_affiliation_number", "insured_relation", "insured_name", "insured_cin", "consent_notes");

    /** What stays on the shell: keys, the clinic's own reference, flags and dates that identify no one. */
    public static final List<String> KEPT_COLUMNS = List.of("id", "status", "created_at", "updated_at", "deleted_at", "deleted_by", "version",
            "practice_id", "primary_practitioner_id", "patient_code", "global_discount_pct", "preferred_language", "merged_into_id",
            "acquisition_channel", "consent_given_at", "anonymised_at");

    /** The patient and any record merged into them: the row stays for the financial records, with nobody left in it. */
    private void wipeIdentity(UUID patientId) {
        UUID actor = null;
        try {
            actor = currentUser.requireUserId();
        } catch (RuntimeException ignored) {
            // A job or a test with nobody signed in: the shell is archived without an author.
        }
        jdbc.update("""
                UPDATE patients SET first_name = 'Anonymisé', last_name = 'Patient', date_of_birth = NULL, gender = NULL, email = NULL, phone = NULL,
                    address = NULL, cin = NULL, guardian_name = NULL, guardian_phone = NULL, photo_file_id = NULL, occupation = NULL,
                    referral_source = NULL, insurer_id = NULL, insurance_provider = NULL, insurance_number = NULL,
                    insurance_affiliation_number = NULL, insured_relation = 'SELF', insured_name = NULL, insured_cin = NULL, consent_notes = NULL,
                    anonymised_at = now(), deleted_at = COALESCE(deleted_at, now()), deleted_by = COALESCE(deleted_by, ?), updated_at = now()
                WHERE id = ? OR merged_into_id = ?
                """, actor, patientId, patientId);
    }
}
