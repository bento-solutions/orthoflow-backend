package com.orthoflow.sterilization;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.*;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.EndoDtos.KitFileRequest;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.EndoDtos.KitView;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.EndoDtos.ModelRequest;
import com.orthoflow.sterilization.application.service.CycleService;
import com.orthoflow.sterilization.application.service.EndoService;
import com.orthoflow.sterilization.application.service.SterilizationService;
import com.orthoflow.sterilization.application.service.TraceabilityService;
import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlResult;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sterilization workflow through the real services and a real database: loading a
 * cycle, releasing or recalling it, using items on patients, and the endo file limit.
 */
class SterilizationFlowTest extends SpringDbTest {

    @Autowired
    private SterilizationService items;
    @Autowired
    private CycleService cycles;
    @Autowired
    private EndoService endo;
    @Autowired
    private TraceabilityService traceability;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID user;
    private UUID patient;
    private UUID otherPatient;
    private UUID autoclave;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        otherPatient = PostgresTestSupport.patient(jdbc, practice, "Karim", "Alaoui", null, null, null);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'Amina', 'Idrissi', 'ASSISTANT', ?)",
                user, user + "@x.ma", practice);
        autoclave = cycles.createAutoclave(practice, new AutoclaveRequest("Autoclave 1", "Vacuklav", null, null)).id();
    }

    private UUID newItem(String code, Kind kind) {
        return items.create(practice, user, new ItemRequest(code, "Item " + code, kind, null, null, false)).id();
    }

    private CycleView load(String type, UUID... ids) {
        return cycles.create(practice, user, new CycleRequest(autoclave, "134C-18min", null, null,
                type == null ? null : com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlType.valueOf(type), List.of(ids), null));
    }

    private State stateOf(UUID id) {
        return items.get(practice, id).state();
    }

    @Test
    void aNewItemStartsDirtyAndALoadIsReleasedOnAPassedControl() {
        UUID tray = newItem("KIT-1", Kind.TRAY);
        UUID handpiece = newItem("HP-1", Kind.HANDPIECE);
        assertThat(stateOf(tray)).isEqualTo(State.DIRTY);

        CycleView cycle = load(null, tray, handpiece);

        assertThat(cycle.summary().number()).isEqualTo(1);
        assertThat(cycle.summary().itemCount()).isEqualTo(2);
        assertThat(cycle.items()).extracting(ItemView::state).containsOnly(State.PROCESSED);
        assertThat(cycle.warnings()).as("a handpiece never lubricated").anyMatch(w -> w.contains("HP-1"));

        CycleView released = cycles.recordControl(practice, user, cycle.summary().id(), new ControlRequest(ControlResult.PASSED, "indicator green"));

        assertThat(released.summary().controlResult()).isEqualTo(ControlResult.PASSED);
        assertThat(released.items()).extracting(ItemView::state).containsOnly(State.READY);
        assertThat(traceability.forItem(practice, tray)).extracting(TraceEntry::action).containsExactly("RELEASED", "PROCESSED", "REGISTERED");
    }

    @Test
    void cycleNumbersCountUpPerMachine() {
        UUID a = newItem("A", Kind.TRAY);
        UUID b = newItem("B", Kind.TRAY);
        assertThat(load(null, a).summary().number()).isEqualTo(1);
        assertThat(load(null, b).summary().number()).isEqualTo(2);
    }

    @Test
    void onlyDirtyItemsCanBeLoadedAndARefusedLoadChangesNothing() {
        UUID tray = newItem("KIT-1", Kind.TRAY);
        UUID other = newItem("KIT-2", Kind.TRAY);
        load(null, tray);

        assertThatThrownBy(() -> load(null, other, tray)).isInstanceOf(ConflictException.class).hasMessageContaining("PROCESSED");

        assertThat(stateOf(other)).as("the item that could have been loaded was not").isEqualTo(State.DIRTY);
        assertThat(cycles.list(practice, null, null, null, null, java.time.ZoneOffset.UTC)).hasSize(1);
    }

    @Test
    void usingAnItemRecordsThePatientAndTheCycleThatSterilisedIt() {
        UUID tray = newItem("KIT-1", Kind.TRAY);
        CycleView cycle = load(null, tray);
        cycles.recordControl(practice, user, cycle.summary().id(), new ControlRequest(ControlResult.PASSED, null));

        assertThat(items.use(practice, user, tray, new UseRequest(patient, null, "bonding")).state()).isEqualTo(State.USED);

        List<TraceEntry> trace = traceability.forPatient(practice, patient);
        assertThat(trace).hasSize(1);
        assertThat(trace.get(0).cycleId()).isEqualTo(cycle.summary().id());
        assertThat(trace.get(0).cycleNumber()).isEqualTo(1);
        assertThat(trace.get(0).note()).isEqualTo("bonding");
        assertThatThrownBy(() -> items.use(practice, user, tray, new UseRequest(otherPatient, null, null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void aFailedControlRecallsTheUnusedAndNamesWhoTheUsedOnesTouched() {
        UUID used = newItem("USED", Kind.TRAY);
        UUID idle = newItem("IDLE", Kind.TRAY);
        CycleView cycle = load("BIOLOGICAL", used, idle);
        UUID id = cycle.summary().id();
        cycles.recordControl(practice, user, id, new ControlRequest(ControlResult.PASSED, "chemical indicator"));
        items.use(practice, user, used, new UseRequest(patient, null, null));

        CycleView failed = cycles.recordControl(practice, user, id, new ControlRequest(ControlResult.FAILED, "spores viables"));

        assertThat(failed.summary().controlResult()).isEqualTo(ControlResult.FAILED);
        assertThat(stateOf(idle)).as("sterile on the shelf, so recalled").isEqualTo(State.DIRTY);
        assertThat(stateOf(used)).as("already on a patient, so left where it is").isEqualTo(State.USED);

        Exposure exposure = cycles.exposure(practice, id);
        assertThat(exposure.uses()).extracting(TraceEntry::patientName).containsExactly("Sara Benziane");
        assertThat(traceability.forItem(practice, idle)).extracting(TraceEntry::action).contains("RECALLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_outbox WHERE practice_id = ? AND subject LIKE 'Cycle de stérilisation en échec%'",
                Integer.class, practice)).as("staff were told").isGreaterThanOrEqualTo(1);

        assertThatThrownBy(() -> cycles.recordControl(practice, user, id, new ControlRequest(ControlResult.PASSED, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aHandpieceIsLubricatedAfterCleaningAndTheWarningGoesAway() {
        UUID handpiece = newItem("HP-1", Kind.HANDPIECE);
        CycleView first = load(null, handpiece);
        cycles.recordControl(practice, user, first.summary().id(), new ControlRequest(ControlResult.PASSED, null));
        items.use(practice, user, handpiece, new UseRequest(patient, null, null));
        items.sendToCleaning(practice, user, handpiece);

        assertThat(items.dashboard(practice, null).lubricationDue()).extracting(ItemView::code).containsExactly("HP-1");
        assertThat(load(null, handpiece).warnings()).isNotEmpty();
    }

    @Test
    void lubricatingFirstClearsIt() {
        UUID handpiece = newItem("HP-2", Kind.HANDPIECE);
        CycleView first = load(null, handpiece);
        cycles.recordControl(practice, user, first.summary().id(), new ControlRequest(ControlResult.PASSED, null));
        items.use(practice, user, handpiece, new UseRequest(patient, null, null));
        items.sendToCleaning(practice, user, handpiece);

        items.lubricate(practice, user, handpiece, "spray");

        assertThat(items.dashboard(practice, null).lubricationDue()).isEmpty();
        assertThat(load(null, handpiece).warnings()).isEmpty();
    }

    @Test
    void anEndoKitStopsWorkingWhenAFileIsSpentAndTheRefusedUseChangesNothing() {
        UUID model = endo.createModel(practice, new ModelRequest("X2", "Dentsply", "25/.06", 2, null)).id();
        UUID kit = newItem("ENDO-1", Kind.ENDO_KIT);
        endo.addFiles(practice, kit, new KitFileRequest(model, 1));

        for (int use = 1; use <= 2; use++) {
            CycleView cycle = load(null, kit);
            cycles.recordControl(practice, user, cycle.summary().id(), new ControlRequest(ControlResult.PASSED, null));
            items.use(practice, user, kit, new UseRequest(patient, null, null));
            items.sendToCleaning(practice, user, kit);
        }
        KitView afterTwo = endo.kit(practice, kit);
        assertThat(afterTwo.files().get(0).useCount()).isEqualTo(2);
        assertThat(afterTwo.needsReplacement()).isTrue();
        assertThat(endo.alerts(practice)).hasSize(1);

        CycleView third = load(null, kit);
        cycles.recordControl(practice, user, third.summary().id(), new ControlRequest(ControlResult.PASSED, null));
        assertThatThrownBy(() -> items.use(practice, user, kit, new UseRequest(patient, null, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("reached its limit");

        assertThat(stateOf(kit)).as("the refused use left the kit sterile").isEqualTo(State.READY);
        assertThat(endo.kit(practice, kit).files().get(0).useCount()).isEqualTo(2);
        assertThat(traceability.forPatient(practice, patient)).hasSize(2);
    }

    @Test
    void replacingTheSpentFileMakesTheKitUsableAgain() {
        UUID model = endo.createModel(practice, new ModelRequest("Single", null, null, 1, null)).id();
        UUID kit = newItem("ENDO-2", Kind.ENDO_KIT);
        UUID file = endo.addFiles(practice, kit, new KitFileRequest(model, 1)).files().get(0).id();
        CycleView cycle = load(null, kit);
        cycles.recordControl(practice, user, cycle.summary().id(), new ControlRequest(ControlResult.PASSED, null));
        items.use(practice, user, kit, new UseRequest(patient, null, null));
        items.sendToCleaning(practice, user, kit);
        CycleView again = load(null, kit);
        cycles.recordControl(practice, user, again.summary().id(), new ControlRequest(ControlResult.PASSED, null));
        assertThatThrownBy(() -> items.use(practice, user, kit, new UseRequest(patient, null, null))).isInstanceOf(ConflictException.class);

        endo.discard(practice, file, "limit reached");
        endo.addFiles(practice, kit, new KitFileRequest(model, 1));

        assertThat(items.use(practice, user, kit, new UseRequest(otherPatient, null, null)).state()).isEqualTo(State.USED);
    }

    @Test
    void aRetiredItemCannotBeUsedOrLoaded() {
        UUID tray = newItem("OLD", Kind.TRAY);
        items.retire(practice, user, tray, "broken");

        assertThatThrownBy(() -> load(null, tray)).isInstanceOf(ConflictException.class).hasMessageContaining("retired");
    }
}
