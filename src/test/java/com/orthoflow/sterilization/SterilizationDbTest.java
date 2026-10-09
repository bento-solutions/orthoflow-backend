package com.orthoflow.sterilization;

import com.orthoflow.sterilization.application.dto.SterilizationDtos.CycleSummary;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.TraceEntry;
import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlResult;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import com.orthoflow.sterilization.infrastructure.SterilizationQuery;
import com.orthoflow.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sterilization register against a real database: tracing a patient and a failed
 * cycle forward, the counts the dashboard shows, and the constraints that keep cycle
 * numbers honest and a deleted patient's record anonymous rather than gone.
 */
@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class SterilizationDbTest {

    private JdbcTemplate jdbc;
    private SterilizationQuery query;
    private UUID practice;
    private UUID patient;
    private UUID user;
    private UUID autoclave;
    private final OffsetDateTime now = OffsetDateTime.now();

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        query = new SterilizationQuery(new NamedParameterJdbcTemplate(jdbc));
        practice = PostgresTestSupport.newPractice(jdbc);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'Amina', 'Idrissi', 'ASSISTANT', ?)", user, user + "@x.ma", practice);
        autoclave = UUID.randomUUID();
        jdbc.update("INSERT INTO autoclaves (id, practice_id, name) VALUES (?, ?, 'Autoclave 1')", autoclave, practice);
    }

    private UUID item(String code, String state, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sterilization_items (id, practice_id, code, name, kind, qr_token, state, active) VALUES (?, ?, ?, ?, 'TRAY', ?, ?, ?)",
                id, practice, code, "Tray " + code, id.toString().replace("-", ""), state, active);
        return id;
    }

    private UUID cycle(int number, String result) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sterilization_cycles (id, practice_id, autoclave_id, cycle_number, program, started_at, operator_id, control_result) VALUES (?, ?, ?, ?, '134C', ?, ?, ?)",
                id, practice, autoclave, number, now.minusHours(1), user, result);
        return id;
    }

    private void event(UUID item, String action, UUID cycle, UUID patientId, OffsetDateTime at) {
        jdbc.update("INSERT INTO sterilization_events (id, practice_id, item_id, action, occurred_at, performed_by, cycle_id, patient_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), practice, item, action, at, user, cycle, patientId);
    }

    @Test
    void whatTouchedAPatientIsTracedToTheCycleThatSterilisedIt() {
        UUID c1 = cycle(1, "PASSED");
        UUID tray = item("KIT-1", "USED", true);
        UUID other = PostgresTestSupport.patient(jdbc, practice, "Karim", "Alaoui", null, null, null);
        event(tray, "PROCESSED", c1, null, now.minusHours(1));
        event(tray, "USED", c1, patient, now.minusMinutes(30));
        event(tray, "USED", c1, other, now.minusMinutes(10));

        List<TraceEntry> forSara = query.usedOnPatient(practice, patient);

        assertThat(forSara).hasSize(1);
        TraceEntry e = forSara.get(0);
        assertThat(e.itemCode()).isEqualTo("KIT-1");
        assertThat(e.patientName()).isEqualTo("Sara Benziane");
        assertThat(e.cycleNumber()).isEqualTo(1);
        assertThat(e.autoclaveName()).isEqualTo("Autoclave 1");
        assertThat(e.controlResult()).isEqualTo("PASSED");
        assertThat(e.performedBy()).isEqualTo("Amina Idrissi");
    }

    @Test
    void aFailedCycleIsTracedForwardToEveryoneItsLoadTouched() {
        UUID failed = cycle(1, "FAILED");
        UUID good = cycle(2, "PASSED");
        UUID a = item("A", "DIRTY", true);
        UUID b = item("B", "DIRTY", true);
        UUID other = PostgresTestSupport.patient(jdbc, practice, "Karim", "Alaoui", null, null, null);
        event(a, "USED", failed, patient, now.minusMinutes(50));
        event(b, "USED", failed, other, now.minusMinutes(40));
        event(a, "USED", good, other, now.minusMinutes(5));

        List<TraceEntry> uses = query.usesOfCycle(practice, failed);

        assertThat(uses).extracting(TraceEntry::itemCode).containsExactly("A", "B");
        assertThat(uses).extracting(TraceEntry::patientName).containsExactly("Sara Benziane", "Karim Alaoui");
    }

    @Test
    void theHistoryOfOneItemIsNewestFirstAndAnotherPracticesRowsNeverShow() {
        UUID tray = item("KIT-1", "READY", true);
        event(tray, "REGISTERED", null, null, now.minusDays(2));
        event(tray, "RELEASED", null, null, now.minusDays(1));
        event(tray, "USED", null, patient, now);

        assertThat(query.forItem(practice, tray, 10)).extracting(TraceEntry::action).containsExactly("USED", "RELEASED", "REGISTERED");
        assertThat(query.forItem(UUID.randomUUID(), tray, 10)).isEmpty();
    }

    @Test
    void countsCoverEveryStateAndLeaveOutRetiredItems() {
        item("R1", "READY", true);
        item("R2", "READY", true);
        item("D1", "DIRTY", true);
        item("RETIRED", "READY", false);

        Map<State, Long> counts = query.countsByState(practice);

        assertThat(counts.get(State.READY)).isEqualTo(2);
        assertThat(counts.get(State.DIRTY)).isEqualTo(1);
        assertThat(counts.get(State.USED)).as("a state with nothing in it is still reported").isZero();
        assertThat(counts.get(State.PROCESSED)).isZero();
    }

    @Test
    void cyclesCanBeFilteredByPeriodAndResultAndPendingOnesAreListed() {
        cycle(1, "PASSED");
        UUID pending = cycle(2, "PENDING");
        cycle(3, "FAILED");

        List<CycleSummary> all = query.cycles(practice, now.minusDays(1), now.plusDays(1), null, null);
        assertThat(all).extracting(CycleSummary::number).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(query.cycles(practice, now.minusDays(1), now.plusDays(1), null, ControlResult.FAILED)).extracting(CycleSummary::number).containsExactly(3);
        assertThat(query.cycles(practice, now.plusDays(1), now.plusDays(2), null, null)).isEmpty();
        assertThat(query.pendingControls(practice)).extracting(CycleSummary::id).containsExactly(pending);
        assertThat(all.get(0).operatorName()).isEqualTo("Amina Idrissi");
    }

    @Test
    void anAppointmentOnlyBelongsToItsOwnPatient() {
        UUID other = PostgresTestSupport.patient(jdbc, practice, "Karim", "Alaoui", null, null, null);
        UUID appointment = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, patient_id, date_time, type, status, duration_minutes, practice_id) VALUES (?, ?, now(), 'ADJUSTMENT', 'SCHEDULED', 30, ?)",
                appointment, patient, practice);

        assertThat(query.appointmentBelongsTo(practice, appointment, patient)).isTrue();
        assertThat(query.appointmentBelongsTo(practice, appointment, other)).isFalse();
        assertThat(query.appointmentBelongsTo(UUID.randomUUID(), appointment, patient)).isFalse();
    }

    // ── Constraints ──
    @Test
    void aCycleNumberIsUniquePerMachineButFreeOnAnotherMachine() {
        cycle(1, "PENDING");
        assertThatThrownBy(() -> cycle(1, "PENDING")).isInstanceOf(DataIntegrityViolationException.class);

        UUID second = UUID.randomUUID();
        jdbc.update("INSERT INTO autoclaves (id, practice_id, name) VALUES (?, ?, 'Autoclave 2')", second, practice);
        jdbc.update("INSERT INTO sterilization_cycles (id, practice_id, autoclave_id, cycle_number, program, started_at) VALUES (?, ?, ?, 1, '134C', now())",
                UUID.randomUUID(), practice, second);
    }

    @Test
    void anItemCodeIsUniquePerPracticeAndTheQrTokenIsUniqueEverywhere() {
        item("KIT-1", "DIRTY", true);
        assertThatThrownBy(() -> item("KIT-1", "DIRTY", true)).isInstanceOf(DataIntegrityViolationException.class);

        UUID elsewhere = PostgresTestSupport.newPractice(jdbc);
        jdbc.update("INSERT INTO sterilization_items (id, practice_id, code, name, kind, qr_token) VALUES (?, ?, 'KIT-1', 'x', 'TRAY', ?)",
                UUID.randomUUID(), elsewhere, UUID.randomUUID().toString().replace("-", ""));

        String token = "a".repeat(32);
        jdbc.update("INSERT INTO sterilization_items (id, practice_id, code, name, kind, qr_token) VALUES (?, ?, 'T-A', 'x', 'TRAY', ?)", UUID.randomUUID(), practice, token);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sterilization_items (id, practice_id, code, name, kind, qr_token) VALUES (?, ?, 'T-B', 'x', 'TRAY', ?)",
                UUID.randomUUID(), elsewhere, token)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void erasingAPatientLeavesTheRegisterIntactButAnonymous() {
        UUID tray = item("KIT-1", "USED", true);
        event(tray, "USED", null, patient, now);

        jdbc.update("DELETE FROM patients WHERE id = ?", patient);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sterilization_events WHERE item_id = ?", Integer.class, tray)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT patient_id FROM sterilization_events WHERE item_id = ?", UUID.class, tray)).isNull();
    }

    @Test
    void anUnknownStateOrKindIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sterilization_items (id, practice_id, code, name, kind, qr_token, state) VALUES (?, ?, 'X', 'x', 'TRAY', ?, 'CLEAN')",
                UUID.randomUUID(), practice, UUID.randomUUID().toString().replace("-", ""))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sterilization_items (id, practice_id, code, name, kind, qr_token) VALUES (?, ?, 'Y', 'x', 'GADGET', ?)",
                UUID.randomUUID(), practice, UUID.randomUUID().toString().replace("-", ""))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
