package com.orthoflow.clinical;

import com.orthoflow.auth.infrastructure.security.JwtService;
import com.orthoflow.clinical.application.dto.PerioChartDtos.Chart;
import com.orthoflow.clinical.application.dto.PerioChartDtos.ChartListItem;
import com.orthoflow.clinical.application.dto.PerioChartDtos.CreateChartRequest;
import com.orthoflow.clinical.application.dto.PerioChartDtos.SaveChartRequest;
import com.orthoflow.clinical.application.service.PerioChartService;
import com.orthoflow.clinical.domain.perio.ToothMeasurements;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Periodontal charts through the real service, JPA and PostgreSQL: the history of a patient's
 * examinations, the lock that stops two screens overwriting each other, what comes back after a
 * round trip through the JSON columns, deletion that can be undone, and clinics that cannot see
 * one another's charts.
 */
@AutoConfigureMockMvc
class PerioChartFlowTest extends SpringDbTest {

    @Autowired
    private PerioChartService service;
    @Autowired
    private JwtService jwt;
    @Autowired
    private MockMvc mockMvc;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID doctor;
    private UUID patient;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        doctor = user(practice, "DOCTOR");
        signInAs(doctor, practice);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "1988-05-02", null, null);
    }

    private UUID user(UUID clinic, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', ?, ?)",
                id, id + "@x.ma", role, clinic);
        return id;
    }

    private Chart newChart(LocalDate on) {
        return service.create(practice, doctor, patient, new CreateChartRequest(on, null));
    }

    private static ToothMeasurements probe(Map<String, Integer> depths, List<String> bleeding) {
        return new ToothMeasurements(null, Map.of(), depths, Map.of(), bleeding, List.of(), List.of());
    }

    private SaveChartRequest save(Chart chart, Map<String, String> status, Map<String, ToothMeasurements> teeth) {
        return new SaveChartRequest(chart.version(), chart.chartDate(), chart.visitReason(), chart.clinicalNotes(), status, teeth);
    }

    @Test
    void aNewChartIsEmptyAndDatedToday() {
        Chart chart = newChart(null);

        assertThat(chart.chartDate()).isEqualTo(LocalDate.now());
        assertThat(chart.teeth()).isEmpty();
        assertThat(chart.toothStatus()).isEmpty();
        assertThat(chart.version()).isZero();
        assertThat(chart.summary().sitesRecorded()).isZero();
    }

    @Test
    void measurementsSurviveASaveAndAReload() {
        Chart chart = newChart(LocalDate.of(2026, 9, 1));
        Map<String, ToothMeasurements> teeth = new LinkedHashMap<>();
        teeth.put("16", new ToothMeasurements(2, Map.of("buccal", "II", "palatal_distal", "I"),
                Map.of("mesiobuccal", 3, "buccal", 4, "distobuccal", 6), Map.of("buccal", -1),
                List.of("buccal"), List.of("mesiobuccal"), List.of("distobuccal")));

        Chart saved = service.save(doctor, patient, chart.id(), save(chart, Map.of("26", "implant"), teeth));
        Chart reloaded = service.get(patient, chart.id());

        assertThat(saved.version()).isEqualTo(1);
        assertThat(reloaded.toothStatus()).containsExactly(Map.entry("26", "implant"));
        ToothMeasurements back = reloaded.teeth().get("16");
        assertThat(back.mobility()).isEqualTo(2);
        assertThat(back.furcation()).containsEntry("buccal", "II").containsEntry("palatal_distal", "I");
        // jsonb keeps keys in its own order, so only the content is compared, not the order
        assertThat(back.pocketDepth()).containsOnly(Map.entry("mesiobuccal", 3), Map.entry("buccal", 4), Map.entry("distobuccal", 6));
        assertThat(back.recession()).containsEntry("buccal", -1);
        assertThat(back.bleeding()).containsExactly("buccal");
        assertThat(reloaded.summary().sitesRecorded()).isEqualTo(3);
        assertThat(reloaded.summary().deepestPocketDepth()).isEqualTo(6);
    }

    @Test
    void teethWithNothingChartedAreNotStored() {
        Chart chart = newChart(null);
        Map<String, ToothMeasurements> teeth = new LinkedHashMap<>();
        teeth.put("11", ToothMeasurements.NONE);
        teeth.put("12", probe(Map.of("buccal", 2), List.of()));

        service.save(doctor, patient, chart.id(), save(chart, Map.of(), teeth));

        assertThat(service.get(patient, chart.id()).teeth()).containsOnlyKeys("12");
        assertThat(jdbc.queryForObject("SELECT teeth::text FROM perio_charts WHERE id = ?", String.class, chart.id()))
                .doesNotContain("\"11\"");
    }

    @Test
    void aSaveFromAStaleScreenIsRefusedRatherThanOverwritingTheNewerOne() {
        Chart chart = newChart(null);
        service.save(doctor, patient, chart.id(), save(chart, Map.of(), Map.of("11", probe(Map.of("buccal", 2), List.of()))));

        // a second screen still showing version 0 tries to save
        assertThatThrownBy(() -> service.save(doctor, patient, chart.id(), save(chart, Map.of(), Map.of("11", probe(Map.of("buccal", 9), List.of())))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("changed on another screen");

        assertThat(service.get(patient, chart.id()).teeth().get("11").pocketDepth()).containsEntry("buccal", 2);
    }

    @Test
    void everyChangeMovesTheVersionOnSoTheNextSaveMustBeBuiltOnIt() {
        Chart chart = newChart(null);
        Chart one = service.save(doctor, patient, chart.id(), save(chart, Map.of(), Map.of("11", probe(Map.of("buccal", 2), List.of()))));
        Chart two = service.save(doctor, patient, chart.id(), save(one, Map.of(), Map.of("11", probe(Map.of("buccal", 3), List.of()))));

        assertThat(one.version()).isEqualTo(1);
        assertThat(two.version()).isEqualTo(2);
    }

    @Test
    void savingWhatIsAlreadyThereChangesNothing() {
        Chart chart = newChart(null);
        Chart one = service.save(doctor, patient, chart.id(), save(chart, Map.of(), Map.of("11", probe(Map.of("buccal", 2), List.of()))));
        Chart again = service.save(doctor, patient, chart.id(), save(one, Map.of(), Map.of("11", probe(Map.of("buccal", 2), List.of()))));

        assertThat(again.version()).isEqualTo(one.version());
    }

    @Test
    void invalidMeasurementsAreRefusedAndNothingIsStored() {
        Chart chart = newChart(null);

        assertThatThrownBy(() -> service.save(doctor, patient, chart.id(),
                save(chart, Map.of(), Map.of("11", probe(Map.of("buccal", 40), List.of())))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.save(doctor, patient, chart.id(),
                save(chart, Map.of("11", "missing"), Map.of("11", probe(Map.of("buccal", 3), List.of())))))
                .isInstanceOf(ValidationException.class);

        Chart unchanged = service.get(patient, chart.id());
        assertThat(unchanged.teeth()).isEmpty();
        assertThat(unchanged.version()).isZero();
    }

    @Test
    void aChartCannotBeDatedInTheFuture() {
        Chart chart = newChart(null);
        assertThatThrownBy(() -> service.create(practice, doctor, patient, new CreateChartRequest(LocalDate.now().plusDays(1), null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.save(doctor, patient, chart.id(),
                new SaveChartRequest(chart.version(), LocalDate.now().plusDays(3), null, null, Map.of(), Map.of())))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void theHistoryListsTheMostRecentExaminationFirstWithItsFigures() {
        Chart january = newChart(LocalDate.of(2026, 1, 10));
        Chart june = newChart(LocalDate.of(2026, 6, 20));
        service.save(doctor, patient, june.id(), save(june, Map.of(),
                Map.of("16", probe(Map.of("buccal", 5, "mesiobuccal", 3), List.of("buccal")))));

        List<ChartListItem> history = service.list(patient);

        assertThat(history).extracting(ChartListItem::id).containsExactly(june.id(), january.id());
        assertThat(history.get(0).summary().meanPocketDepth()).isEqualTo(4.0);
        assertThat(history.get(0).summary().bleedingPercent()).isEqualTo(50.0);
        assertThat(history.get(1).summary().sitesRecorded()).isZero();
    }

    @Test
    void aNewChartKeepsWhichTeethAreMissingButNotTheMeasurements() {
        Chart first = newChart(LocalDate.of(2026, 1, 10));
        service.save(doctor, patient, first.id(), save(first, Map.of("18", "missing", "46", "implant"),
                Map.of("11", probe(Map.of("buccal", 3), List.of()))));

        Chart next = newChart(LocalDate.of(2026, 7, 1));

        assertThat(next.toothStatus()).containsEntry("18", "missing").containsEntry("46", "implant");
        assertThat(next.teeth()).isEmpty();
    }

    @Test
    void aDeletedChartLeavesTheHistoryAndCanComeBack() {
        Chart chart = newChart(LocalDate.of(2026, 3, 3));

        service.delete(practice, patient, chart.id());

        assertThat(service.list(patient)).isEmpty();
        assertThat(service.listDeleted(patient)).extracting(ChartListItem::id).containsExactly(chart.id());
        assertThatThrownBy(() -> service.get(patient, chart.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.save(doctor, patient, chart.id(), save(chart, Map.of(), Map.of())))
                .isInstanceOf(NotFoundException.class);

        service.restore(practice, patient, chart.id());

        assertThat(service.list(patient)).extracting(ChartListItem::id).containsExactly(chart.id());
        assertThat(service.listDeleted(patient)).isEmpty();
    }

    @Test
    void creatingDeletingAndRestoringAreRecordedInTheActivityLog() {
        Chart chart = newChart(null);
        service.delete(practice, patient, chart.id());
        service.restore(practice, patient, chart.id());

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM activity_events WHERE entity_id = ? AND action LIKE 'PERIO_CHART_%' ORDER BY created_at",
                String.class, patient);
        assertThat(actions).containsExactly("PERIO_CHART_CREATED", "PERIO_CHART_DELETED", "PERIO_CHART_RESTORED");
    }

    @Test
    void anotherClinicCannotSeeOrTouchTheChart() {
        Chart chart = newChart(null);
        UUID otherClinic = PostgresTestSupport.newPractice(jdbc);
        UUID otherDoctor = user(otherClinic, "DOCTOR");
        signInAs(otherDoctor, otherClinic);

        assertThatThrownBy(() -> service.get(patient, chart.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.list(patient)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.save(otherDoctor, patient, chart.id(), save(chart, Map.of(), Map.of())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deletingThePatientTakesTheirChartsWithIt() {
        Chart chart = newChart(null);
        jdbc.update("DELETE FROM patients WHERE id = ?", patient);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM perio_charts WHERE id = ?", Integer.class, chart.id())).isZero();
    }

    // ── over HTTP: the role floor and the wire format ─────────────────────

    private String bearer(UUID user, String role) {
        return "Bearer " + jwt.generateToken(user, user + "@x.ma", role);
    }

    @Test
    void overHttpADoctorChartsAndTheFrontDeskCannotEvenLook() throws Exception {
        signOut(); // the signed-in test user has no role: the requests below carry their own token
        UUID assistant = user(practice, "ASSISTANT");
        String base = "/patients/" + patient + "/clinical-record/perio-charts";

        String created = mockMvc.perform(post(base).header("Authorization", bearer(doctor, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"chartDate\":\"2026-09-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mockMvc.perform(put(base + "/" + id).header("Authorization", bearer(doctor, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"toothStatus":{"28":"missing"},
                                 "teeth":{"16":{"pocketDepth":{"buccal":4},"bleeding":["buccal"],"furcation":{"buccal":"I"}}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.teeth['16'].pocketDepth.buccal").value(4))
                .andExpect(jsonPath("$.teeth['16'].mobility").doesNotExist())
                .andExpect(jsonPath("$.summary.bleedingPercent").value(100.0));

        mockMvc.perform(put(base + "/" + id).header("Authorization", bearer(doctor, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"toothStatus\":{},\"teeth\":{}}"))
                .andExpect(status().isConflict());

        mockMvc.perform(put(base + "/" + id).header("Authorization", bearer(doctor, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"toothStatus\":{},\"teeth\":{\"16\":{\"pocketDepth\":{\"buccal\":99}}}}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(base).header("Authorization", bearer(assistant, "ASSISTANT"))).andExpect(status().isForbidden());
        mockMvc.perform(post(base).header("Authorization", bearer(assistant, "ASSISTANT"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(get(base)).andExpect(status().isUnauthorized());

        mockMvc.perform(delete(base + "/" + id).header("Authorization", bearer(doctor, "DOCTOR")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(base + "?deleted=true").header("Authorization", bearer(doctor, "DOCTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
    }
}
