package com.orthoflow.compliance;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.compliance.application.dto.PatientDataExport;
import com.orthoflow.compliance.application.service.DataExportService;
import com.orthoflow.compliance.application.service.PatientDataPurger;
import com.orthoflow.compliance.application.service.PatientDataRegistry;
import com.orthoflow.compliance.application.service.PublicIntakeRetentionJob;
import com.orthoflow.patient.application.service.PatientService;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rights of the person the clinic holds data about (Law 09-08): to receive a copy of all of it, to have
 * it erased, and, where the law makes the clinic keep the financial records, to have it anonymised. And the
 * guard that makes a table added next year part of all three or fail the build.
 */
class PatientDataRightsTest extends SpringDbTest {

    @Autowired
    private PatientService patients;
    @Autowired
    private DataExportService exports;
    @Autowired
    private PatientDataRegistry registry;
    @Autowired
    private PublicIntakeRetentionJob intakeRetention;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID admin;
    private UUID patient;

    @BeforeEach
    void aPatientWithARecord() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        admin = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)",
                admin, admin + "@x.ma", practice);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2010-01-01", "0611223344", "AB123456");
        jdbc.update("UPDATE patients SET email = 'sara@example.com', address = '12 rue des Fleurs', guardian_name = 'Karim Benziane', insured_name = 'Karim Benziane' WHERE id = ?", patient);
        signInAs(admin, practice);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private UUID invoice() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, currency, total, region_code, created_by, notes) VALUES (?, ?, ?, ?, 'DRAFT', 'MAD', 1500, 'MA', ?, 'Mme Benziane paie en deux fois')",
                id, practice, patient, "INV-" + id.toString().substring(0, 8), admin);
        jdbc.update("INSERT INTO invoice_lines (id, invoice_id, act_code, label, quantity, unit_price, discount_pct, line_total, sort_order, practice_id) VALUES (?, ?, 'D700', 'Obturation', 1, 1500, 0, 1500, 0, ?)",
                UUID.randomUUID(), id, practice);
        return id;
    }

    private void clinicalAndContactData() {
        jdbc.update("INSERT INTO appointments (id, practice_id, patient_id, date_time, type, status) VALUES (?, ?, ?, now(), 'x', 'SCHEDULED')", UUID.randomUUID(), practice, patient);
        jdbc.update("INSERT INTO clinical_notes (id, patient_id, category, content, author_id, practice_id) VALUES (?, ?, 'GENERAL', 'allergie penicilline', ?, ?)", UUID.randomUUID(), patient, admin, practice);
        jdbc.update("INSERT INTO prescriptions (id, practice_id, number, patient_id, lines, status) VALUES (?, ?, ?, ?, '[{\"drug\":\"Amoxicilline\"}]'::jsonb, 'ISSUED')",
                UUID.randomUUID(), practice, "RX-" + UUID.randomUUID().toString().substring(0, 8), patient);
        jdbc.update("""
                INSERT INTO insurance_forms (id, practice_id, number, patient_id, form_code, purpose, source, care_date, lines, total, snapshot, status)
                VALUES (?, ?, ?, ?, 'CNOPS', 'EXECUTION', 'MANUAL', current_date, '[]'::jsonb, 100, '{"name":"Sara Benziane"}'::jsonb, 'TO_PRINT')
                """, UUID.randomUUID(), practice, "IF-" + UUID.randomUUID().toString().substring(0, 8), patient);
        jdbc.update("INSERT INTO tasks (id, practice_id, title, patient_id) VALUES (?, ?, 'Rappeler Sara Benziane', ?)", UUID.randomUUID(), practice, patient);
        jdbc.update("INSERT INTO patient_phones (id, patient_id, number, practice_id) VALUES (?, ?, '0655443322', ?)", UUID.randomUUID(), patient, practice);
        jdbc.update("INSERT INTO message_outbox (id, practice_id, channel, purpose, recipient, patient_id, body) VALUES (?, ?, 'WHATSAPP', 'BOOKING_CONFIRMED', '212611223344', ?, 'Bonjour Sara')",
                UUID.randomUUID(), practice, patient);
        jdbc.update("INSERT INTO activity_events (id, practice_id, entity_type, entity_id, action, diff) VALUES (?, ?, 'PATIENT', ?, 'CREATED', '{\"firstName\":\"Sara\"}')",
                UUID.randomUUID(), practice, patient);
        UUID type = UUID.randomUUID();
        jdbc.update("INSERT INTO appointment_types (id, practice_id, code, name_fr, name_en, name_ar) VALUES (?, ?, 'CONS', 'Consultation', 'Consultation', 'x')", type, practice);
        // From the same person, never linked to the record: a declined request, a registration form, a message from the number, the landing contact.
        jdbc.update("INSERT INTO booking_requests (id, practice_id, appointment_type_id, starts_at, duration_minutes, first_name, last_name, phone, status) VALUES (?, ?, ?, now() + interval '3 days', 30, 'sara', 'BENZIANE', '06 11 22 33 44', 'DECLINED')",
                UUID.randomUUID(), practice, type);
        jdbc.update("INSERT INTO pending_patients (id, practice_id, first_name, last_name, email, consented_at) VALUES (?, ?, 'Sara', 'Benziane', 'SARA@example.com', now())", UUID.randomUUID(), practice);
        jdbc.update("INSERT INTO message_events (id, practice_id, direction, event_type, from_phone, body) VALUES (?, ?, 'IN', 'MESSAGE', '212611223344', 'Bonjour, je voudrais un rendez-vous')", UUID.randomUUID(), practice);
        jdbc.update("INSERT INTO landing_page_contacts (practice_id, phone_key, source) VALUES (?, '611223344', 'BOOKING')", practice);
        // And someone else who shares nothing with her but a surname: untouched by her erasure.
        jdbc.update("INSERT INTO booking_requests (id, practice_id, appointment_type_id, starts_at, duration_minutes, first_name, last_name, phone, status) VALUES (?, ?, ?, now() + interval '3 days', 30, 'Omar', 'Benziane', '0699887766', 'PENDING')",
                UUID.randomUUID(), practice, type);
    }

    // ── the guard ──
    @Test
    void everyTableThatPointsAtAPatientHasAnErasureDecision() {
        assertThat(registry.unclassified()).as("tables with patient data and no rule in PatientDataRegistry").isEmpty();
    }

    @Test
    void everyColumnOfPatientsIsEitherWipedOrDeclaredToIdentifyNoOne() {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'patients'", String.class);
        List<String> decided = new ArrayList<>(PatientDataPurger.WIPED_COLUMNS);
        decided.addAll(PatientDataPurger.KEPT_COLUMNS);

        assertThat(columns).as("patients columns with no anonymisation decision in PatientDataPurger").isSubsetOf(decided);
    }

    // ── export ──
    @Test
    void theExportHoldsEveryTableThatHoldsSomethingAboutThePatient() {
        clinicalAndContactData();
        invoice();

        PatientDataExport export = exports.exportPatientData(patient);

        assertThat(export.getRecords().keySet()).contains("prescriptions", "insurance_forms", "tasks", "message_outbox", "patient_phones",
                "invoices", "invoice_lines", "clinical_notes", "appointments");
        assertThat(export.getRecords().get("invoice_lines")).hasSize(1);
        assertThat(export.getRecords().get("prescriptions").get(0).toString()).contains("Amoxicilline");
        assertThat(export.getCopiesWithoutLink().keySet()).contains("booking_requests", "pending_patients", "message_events", "landing_page_contacts");
        // The other Benziane's request is hers alone.
        assertThat(export.getCopiesWithoutLink().get("booking_requests")).hasSize(1);
    }

    // ── erasure ──
    @Test
    void aPatientWithOnlyAPrescriptionAndAnInsuranceFormCanBeErased() {
        clinicalAndContactData();

        patients.erasePatient(patient);

        assertThat(count("SELECT count(*) FROM patients WHERE id = ?", patient)).isZero();
        for (String table : List.of("appointments", "clinical_notes", "prescriptions", "insurance_forms", "tasks", "patient_phones", "message_outbox")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE patient_id = ?", patient)).as(table).isZero();
        }
    }

    @Test
    void erasureAlsoRemovesTheCopiesNothingLinkedButLeavesAnotherPersonsRequest() {
        clinicalAndContactData();

        patients.erasePatient(patient);

        assertThat(count("SELECT count(*) FROM pending_patients WHERE practice_id = ?", practice)).isZero();
        assertThat(count("SELECT count(*) FROM message_events WHERE practice_id = ?", practice)).isZero();
        assertThat(count("SELECT count(*) FROM landing_page_contacts WHERE practice_id = ?", practice)).isZero();
        assertThat(count("SELECT count(*) FROM activity_events WHERE practice_id = ? AND entity_id = ?", practice, patient)).isZero();
        assertThat(jdbc.queryForList("SELECT first_name FROM booking_requests WHERE practice_id = ?", String.class, practice)).containsExactly("Omar");
    }

    @Test
    void anArchivedPatientCanStillBeErased() {
        jdbc.update("UPDATE patients SET deleted_at = now() WHERE id = ?", patient);

        patients.erasePatient(patient);

        assertThat(count("SELECT count(*) FROM patients WHERE id = ?", patient)).isZero();
    }

    @Test
    void aRecordMergedIntoThePatientGoesWithThem() {
        UUID duplicate = PostgresTestSupport.patient(jdbc, practice, "Sarah", "Benziane", "2010-01-01", "0600000000", null);
        jdbc.update("UPDATE patients SET merged_into_id = ?, deleted_at = now() WHERE id = ?", patient, duplicate);

        patients.erasePatient(patient);

        assertThat(count("SELECT count(*) FROM patients WHERE id IN (?, ?)", patient, duplicate)).isZero();
    }

    @Test
    void anInvoicedPatientCannotBeErasedAndIsToldToAnonymise() {
        invoice();

        assertThatThrownBy(() -> patients.erasePatient(patient)).isInstanceOf(ConflictException.class).hasMessageContaining("Anonymise");
        assertThat(count("SELECT count(*) FROM patients WHERE id = ?", patient)).isOne();
    }

    @Test
    void aPatientWithOnlyAStockInvoiceOrSalesOrderIsRefusedClearlyNotWithAForeignKeyError() {
        jdbc.update("INSERT INTO sales_orders (id, practice_id, so_number, patient_id, total_amount) VALUES (?, ?, ?, ?, 10)", UUID.randomUUID(), practice, "SO-" + UUID.randomUUID().toString().substring(0, 8), patient);

        assertThatThrownBy(() -> patients.erasePatient(patient)).isInstanceOf(ConflictException.class).hasMessageContaining("financial record");
    }

    // ── anonymisation ──
    @Test
    void anonymisingKeepsTheFinancialRecordsAndRemovesTheIdentityAndEverythingClinical() {
        clinicalAndContactData();
        UUID invoice = invoice();
        jdbc.update("INSERT INTO receipts (id, practice_id, patient_id, amount, method, receipt_date, recorded_by, notes) VALUES (?, ?, ?, 500, 'CASH', current_date, ?, 'Sara Benziane a payé')",
                UUID.randomUUID(), practice, patient, admin);
        jdbc.update("INSERT INTO cheques (id, practice_id, number, amount, due_date, patient_id, drawer_name) VALUES (?, ?, 'CH-1', 700, current_date, ?, 'Karim Benziane')", UUID.randomUUID(), practice, patient);

        patients.anonymisePatient(patient);

        // The shell: kept, archived, with nobody in it.
        var shell = jdbc.queryForMap("SELECT * FROM patients WHERE id = ?", patient);
        assertThat(shell.get("first_name")).isEqualTo("Anonymisé");
        assertThat(shell.get("last_name")).isEqualTo("Patient");
        for (String column : List.of("date_of_birth", "email", "phone", "address", "cin", "guardian_name", "insured_name")) {
            assertThat(shell.get(column)).as(column).isNull();
        }
        assertThat(shell.get("anonymised_at")).isNotNull();
        assertThat(shell.get("deleted_at")).isNotNull();
        assertThat(shell.get("patient_code")).isNotNull();
        // The financial records: kept, with amounts, numbers and dates, and without free text or a name.
        assertThat(jdbc.queryForMap("SELECT total, notes FROM invoices WHERE id = ?", invoice)).containsEntry("notes", null)
                .satisfies(m -> assertThat(m.get("total").toString()).startsWith("1500"));
        assertThat(count("SELECT count(*) FROM invoice_lines WHERE invoice_id = ?", invoice)).isOne();
        assertThat(jdbc.queryForList("SELECT notes FROM receipts WHERE patient_id = ?", String.class, patient)).containsOnlyNulls();
        assertThat(jdbc.queryForList("SELECT drawer_name FROM cheques WHERE patient_id = ?", String.class, patient)).containsOnlyNulls();
        // Everything clinical and personal: gone.
        for (String table : List.of("appointments", "clinical_notes", "prescriptions", "insurance_forms", "tasks", "patient_phones", "message_outbox")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE patient_id = ?", patient)).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM pending_patients WHERE practice_id = ?", practice)).isZero();
        assertThat(count("SELECT count(*) FROM activity_events WHERE practice_id = ? AND entity_id = ?", practice, patient)).isZero();
    }

    @Test
    void anAnonymisedPatientIsGoneFromTheClinicsViewAndCannotBeAnonymisedTwice() {
        invoice();
        patients.anonymisePatient(patient);

        assertThatThrownBy(() -> patients.getPatientById(patient)).isInstanceOf(NotFoundException.class);
    }

    // ── retention ──
    @Test
    void declinedAndExpiredRequestsAndRejectedFormsAreDeletedAfterTheirPeriodAndNothingElse() {
        UUID type = UUID.randomUUID();
        jdbc.update("INSERT INTO appointment_types (id, practice_id, code, name_fr, name_en, name_ar) VALUES (?, ?, 'CONS', 'Consultation', 'Consultation', 'x')", type, practice);
        String request = "INSERT INTO booking_requests (id, practice_id, appointment_type_id, starts_at, duration_minutes, first_name, last_name, phone, status, created_at, decided_at) "
                + "VALUES (?, ?, ?, now(), 30, ?, 'X', '0600000000', ?, now() - make_interval(days => ?), now() - make_interval(days => ?))";
        jdbc.update(request, UUID.randomUUID(), practice, type, "OldDeclined", "DECLINED", 60, 60);
        jdbc.update(request, UUID.randomUUID(), practice, type, "NewDeclined", "DECLINED", 5, 5);
        jdbc.update(request, UUID.randomUUID(), practice, type, "OldPending", "PENDING", 60, 60);
        jdbc.update("INSERT INTO pending_patients (id, practice_id, first_name, last_name, consented_at, status, created_at) VALUES (?, ?, 'OldRejected', 'X', now(), 'REJECTED', now() - interval '60 days')", UUID.randomUUID(), practice);
        jdbc.update("INSERT INTO pending_patients (id, practice_id, first_name, last_name, consented_at, status, created_at) VALUES (?, ?, 'Waiting', 'X', now(), 'PENDING', now() - interval '60 days')", UUID.randomUUID(), practice);
        jdbc.update("INSERT INTO pending_patients (id, practice_id, first_name, last_name, consented_at, status, created_at) VALUES (?, ?, 'Forgotten', 'X', now(), 'PENDING', now() - interval '200 days')", UUID.randomUUID(), practice);

        intakeRetention.purge();

        assertThat(jdbc.queryForList("SELECT first_name FROM booking_requests WHERE practice_id = ?", String.class, practice)).containsExactlyInAnyOrder("NewDeclined", "OldPending");
        assertThat(jdbc.queryForList("SELECT first_name FROM pending_patients WHERE practice_id = ?", String.class, practice)).containsExactly("Waiting");
    }
}
