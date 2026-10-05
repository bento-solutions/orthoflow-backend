package com.orthoflow.scheduling;

import com.orthoflow.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two people cannot sit in one chair, and one doctor cannot be in two places —
 * enforced by the database because a check-then-insert in Java can be raced by
 * two receptionists clicking at once.
 */
@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class AppointmentConstraintsDbTest {

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID patient;
    private UUID chair;
    private UUID doctor;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        chair = UUID.randomUUID();
        jdbc.update("INSERT INTO chairs (id, practice_id, name) VALUES (?, ?, 'C')", chair, practice);
        doctor = UUID.randomUUID();
        jdbc.update("INSERT INTO practitioners (id, practice_id, display_name) VALUES (?, ?, 'Dr')", doctor, practice);
    }

    private void book(String start, int minutes, UUID chairId, UUID practitionerId, String status) {
        jdbc.update("""
                INSERT INTO appointments (id, practice_id, patient_id, date_time, duration_minutes, type, status, chair_id, practitioner_id)
                VALUES (?, ?, ?, CAST(? AS timestamptz), ?, 'x', ?, ?, ?)
                """, UUID.randomUUID(), practice, patient, start, minutes, status, chairId, practitionerId);
    }

    @Test
    void aChairCannotBeDoubleBooked() {
        book("2030-01-01T09:00:00Z", 30, chair, null, "SCHEDULED");

        assertThatThrownBy(() -> book("2030-01-01T09:15:00Z", 30, chair, null, "SCHEDULED"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("appointments_no_chair_overlap");
        book("2030-01-01T09:30:00Z", 30, chair, null, "SCHEDULED");
    }

    @Test
    void aPractitionerCannotBeDoubleBookedEvenOnTwoChairs() {
        UUID chair2 = UUID.randomUUID();
        jdbc.update("INSERT INTO chairs (id, practice_id, name) VALUES (?, ?, 'C2')", chair2, practice);
        book("2030-01-01T09:00:00Z", 30, chair, doctor, "SCHEDULED");

        assertThatThrownBy(() -> book("2030-01-01T09:10:00Z", 30, chair2, doctor, "ARRIVED"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("appointments_no_practitioner_overlap");
    }

    @Test
    void cancelledAndNoShowVisitsFreeTheSlot() {
        book("2030-01-01T09:00:00Z", 30, chair, doctor, "CANCELLED");
        book("2030-01-01T09:00:00Z", 30, chair, doctor, "NO_SHOW");
        book("2030-01-01T09:00:00Z", 30, chair, doctor, "SCHEDULED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments WHERE practice_id = ?", Integer.class, practice)).isEqualTo(3);
    }

    @Test
    void visitsWithNoChairOrNoPractitionerNeverConflict() {
        book("2030-01-01T09:00:00Z", 30, null, null, "SCHEDULED");
        book("2030-01-01T09:00:00Z", 30, null, null, "SCHEDULED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments WHERE practice_id = ?", Integer.class, practice)).isEqualTo(2);
    }
}
