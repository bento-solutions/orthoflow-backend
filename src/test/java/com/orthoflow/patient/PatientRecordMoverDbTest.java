package com.orthoflow.patient;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.application.service.PatientRecordMover;
import com.orthoflow.patient.application.service.PatientRecordMover.ChartChoice;
import com.orthoflow.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A merge re-points every table that references a patient, found from the
 * database's own foreign keys — so these tests build real rows in real tables
 * and check where they end up.
 */
@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class PatientRecordMoverDbTest {

    private JdbcTemplate jdbc;
    private PatientRecordMover mover;
    private UUID practice;
    private UUID source;
    private UUID target;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        mover = new PatientRecordMover(jdbc);
        practice = PostgresTestSupport.newPractice(jdbc);
        source = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        target = PostgresTestSupport.patient(jdbc, practice, "Sarah", "Benziane", null, null, null);
    }

    private void appointment(UUID patient) {
        jdbc.update("INSERT INTO appointments (id, practice_id, patient_id, date_time, type, status) VALUES (?, ?, ?, now(), 'x', 'SCHEDULED')",
                UUID.randomUUID(), practice, patient);
    }

    private UUID chart(UUID patient) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dental_charts (id, patient_id, chart_type) VALUES (?, ?, 'adult')", id, patient);
        return id;
    }

    private int count(String table, UUID patient) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE patient_id = ?", Integer.class, patient);
    }

    @Test
    void everyTableThatPointsAtThePatientIsMovedIncludingOnesTheCodeNeverNamed() {
        appointment(source);
        appointment(source);
        UUID invoice = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN')", user, user + "@x.ma");
        jdbc.update("INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, currency, total, region_code, created_by) VALUES (?, ?, ?, ?, 'DRAFT', 'MAD', 100, 'MA', ?)",
                invoice, practice, source, "INV-" + invoice.toString().substring(0, 8), user);
        jdbc.update("INSERT INTO receipts (id, practice_id, patient_id, amount, method, receipt_date, recorded_by) VALUES (?, ?, ?, 50, 'CASH', current_date, ?)",
                UUID.randomUUID(), practice, source, user);
        jdbc.update("INSERT INTO patient_phones (id, patient_id, number) VALUES (?, ?, '0600')", UUID.randomUUID(), source);

        assertThat(mover.preview(source)).containsEntry("appointments", 2).containsEntry("invoices", 1)
                .containsEntry("receipts", 1).containsEntry("patient_phones", 1);

        Map<String, Integer> moved = mover.move(source, target, null);

        assertThat(moved).containsEntry("appointments", 2).containsEntry("invoices", 1).containsEntry("receipts", 1);
        assertThat(count("appointments", source)).isZero();
        assertThat(count("appointments", target)).isEqualTo(2);
        assertThat(count("invoices", target)).isEqualTo(1);
        assertThat(count("receipts", target)).isEqualTo(1);
        assertThat(count("patient_phones", target)).isEqualTo(1);
    }

    @Test
    void whenBothHaveADentalChartTheCallerMustChooseAndTheOtherIsDiscarded() {
        UUID sourceChart = chart(source);
        UUID targetChart = chart(target);

        assertThat(mover.bothHaveDentalCharts(source, target)).isTrue();
        assertThatThrownBy(() -> mover.move(source, target, null)).isInstanceOf(ValidationException.class).hasMessageContaining("dental chart");
        assertThat(count("dental_charts", source)).isEqualTo(1);

        mover.move(source, target, ChartChoice.SOURCE);

        assertThat(jdbc.queryForList("SELECT id FROM dental_charts WHERE patient_id = ?", UUID.class, target)).containsExactly(sourceChart);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dental_charts WHERE id = ?", Integer.class, targetChart)).isZero();
    }

    @Test
    void keepingTheTargetsChartDiscardsTheSources() {
        UUID sourceChart = chart(source);
        UUID targetChart = chart(target);

        mover.move(source, target, ChartChoice.TARGET);

        assertThat(jdbc.queryForList("SELECT id FROM dental_charts WHERE patient_id = ?", UUID.class, target)).containsExactly(targetChart);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dental_charts WHERE id = ?", Integer.class, sourceChart)).isZero();
    }

    @Test
    void aChartOnlyTheSourceHasMovesWithoutAChoice() {
        UUID sourceChart = chart(source);

        mover.move(source, target, null);

        assertThat(jdbc.queryForList("SELECT id FROM dental_charts WHERE patient_id = ?", UUID.class, target)).containsExactly(sourceChart);
    }

    @Test
    void consentKeepsTheSurvivorsAnswerWhereBothAnsweredAndMovesTheRest() {
        jdbc.update("INSERT INTO patient_channel_consent (patient_id, channel, opted_in) VALUES (?, 'WHATSAPP', true)", source);
        jdbc.update("INSERT INTO patient_channel_consent (patient_id, channel, opted_in) VALUES (?, 'EMAIL', true)", source);
        jdbc.update("INSERT INTO patient_channel_consent (patient_id, channel, opted_in) VALUES (?, 'WHATSAPP', false)", target);

        mover.move(source, target, null);

        assertThat(jdbc.queryForObject("SELECT opted_in FROM patient_channel_consent WHERE patient_id = ? AND channel = 'WHATSAPP'", Boolean.class, target)).isFalse();
        assertThat(jdbc.queryForObject("SELECT opted_in FROM patient_channel_consent WHERE patient_id = ? AND channel = 'EMAIL'", Boolean.class, target)).isTrue();
        assertThat(count("patient_channel_consent", source)).isZero();
    }

    @Test
    void aPatientCannotBeMergedIntoThemselves() {
        assertThatThrownBy(() -> mover.move(source, source, null)).isInstanceOf(ValidationException.class);
    }
}
