package com.orthoflow.recall;

import com.orthoflow.recall.application.dto.RecallDtos.Filter;
import com.orthoflow.recall.application.dto.RecallDtos.Kind;
import com.orthoflow.recall.application.dto.RecallDtos.Row;
import com.orthoflow.recall.infrastructure.RecallQuery;
import com.orthoflow.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class RecallQueryDbTest {

    private JdbcTemplate jdbc;
    private RecallQuery query;
    private UUID practice;
    private UUID debondType;
    private UUID retentionType;
    private final OffsetDateTime now = OffsetDateTime.now();

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        query = new RecallQuery(new NamedParameterJdbcTemplate(jdbc));
        practice = PostgresTestSupport.newPractice(jdbc);
        debondType = type("DEBOND");
        retentionType = type("RETENTION_CHECK");
    }

    private UUID type(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO appointment_types (id, practice_id, code, name_fr, name_en, name_ar) VALUES (?, ?, ?, ?, ?, ?)",
                id, practice, code, code, code, code);
        return id;
    }

    private UUID patient(String last) {
        return PostgresTestSupport.patient(jdbc, practice, "P", last, null, null, null);
    }

    private void appointment(UUID patient, String when, String status, UUID type) {
        jdbc.update("INSERT INTO appointments (id, practice_id, patient_id, date_time, type, status, appointment_type_id) VALUES (?, ?, ?, " + when + ", 'x', ?, ?)",
                UUID.randomUUID(), practice, patient, status, type);
    }

    private List<Row> run(Kind kind, boolean excludeNeverVisited) {
        return query.run(new Filter(practice, kind, null, null, null, excludeNeverVisited, "name"), now);
    }

    private static List<String> names(List<Row> rows) {
        return rows.stream().map(Row::lastName).toList();
    }

    @Test
    void noVisitListsPatientsWhoseLastVisitIsOldAndWhoHaveNothingBooked() {
        UUID lapsed = patient("Lapsed");
        appointment(lapsed, "now() - interval '5 months'", "COMPLETED", null);
        UUID recent = patient("Recent");
        appointment(recent, "now() - interval '10 days'", "COMPLETED", null);
        UUID booked = patient("Booked");
        appointment(booked, "now() - interval '8 months'", "COMPLETED", null);
        appointment(booked, "now() + interval '3 days'", "SCHEDULED", null);
        patient("NeverCame");

        assertThat(names(run(Kind.NO_VISIT_3M, true))).containsExactly("Lapsed");
        assertThat(names(run(Kind.NO_VISIT_6M, true))).containsExactly();
        assertThat(names(run(Kind.NO_VISIT_3M, false))).containsExactlyInAnyOrder("Lapsed", "NeverCame");
    }

    @Test
    void nothingScheduledLooksOnlyAtTheWindowAhead() {
        UUID soon = patient("Soon");
        appointment(soon, "now() - interval '1 month'", "COMPLETED", null);
        appointment(soon, "now() + interval '10 days'", "SCHEDULED", null);
        UUID later = patient("Later");
        appointment(later, "now() - interval '1 month'", "COMPLETED", null);
        appointment(later, "now() + interval '5 months'", "SCHEDULED", null);
        UUID none = patient("None");
        appointment(none, "now() - interval '1 month'", "COMPLETED", null);

        assertThat(names(run(Kind.NOTHING_SCHEDULED_1M, true))).containsExactlyInAnyOrder("Later", "None");
        assertThat(names(run(Kind.NOTHING_SCHEDULED_12M, true))).containsExactly("None");
    }

    @Test
    void lostToFollowUpIsAnActiveTreatmentWithNoNextAppointment() {
        UUID treatment = UUID.randomUUID();
        jdbc.update("INSERT INTO treatments (id, name, code, base_price) VALUES (?, 'T', ?, 100)", treatment, "T-" + treatment);
        UUID lost = patient("Lost");
        UUID followed = patient("Followed");
        UUID finished = patient("Finished");
        for (UUID p : List.of(lost, followed)) {
            jdbc.update("INSERT INTO patient_treatments (id, patient_id, treatment_id, teeth, status, progress) VALUES (?, ?, ?, '11', 'ACTIVE', 50)",
                    UUID.randomUUID(), p, treatment);
        }
        jdbc.update("INSERT INTO patient_treatments (id, patient_id, treatment_id, teeth, status, progress) VALUES (?, ?, ?, '11', 'COMPLETED', 100)",
                UUID.randomUUID(), finished, treatment);
        appointment(followed, "now() + interval '10 days'", "CONFIRMED", null);

        assertThat(names(run(Kind.LOST_TO_FOLLOW_UP, false))).containsExactly("Lost");
    }

    @Test
    void retentionIsDueSixMonthsAfterDebondUnlessACheckIsAlreadyBooked() {
        UUID due = patient("Due");
        appointment(due, "now() - interval '7 months'", "COMPLETED", debondType);
        UUID tooEarly = patient("TooEarly");
        appointment(tooEarly, "now() - interval '2 months'", "COMPLETED", debondType);
        UUID handled = patient("Handled");
        appointment(handled, "now() - interval '8 months'", "COMPLETED", debondType);
        appointment(handled, "now() - interval '1 month'", "COMPLETED", retentionType);
        UUID booked = patient("Booked");
        appointment(booked, "now() - interval '7 months'", "COMPLETED", debondType);
        appointment(booked, "now() + interval '1 week'", "SCHEDULED", retentionType);

        List<Row> rows = run(Kind.RETENTION_DUE_6M, false);

        assertThat(names(rows)).containsExactly("Due");
        assertThat(rows.get(0).dueSince()).isNotNull().isBefore(now.toLocalDate());
        assertThat(names(run(Kind.RETENTION_DUE_12M, false))).isEmpty();
    }

    @Test
    void progressFiltersAndRemainingWorkSortWork() {
        UUID treatment = UUID.randomUUID();
        jdbc.update("INSERT INTO treatments (id, name, code, base_price) VALUES (?, 'T', ?, 100)", treatment, "T-" + treatment);
        for (Object[] row : new Object[][]{{"Barely", 10}, {"Half", 50}, {"Almost", 90}}) {
            UUID p = patient((String) row[0]);
            appointment(p, "now() - interval '5 months'", "COMPLETED", null);
            jdbc.update("INSERT INTO patient_treatments (id, patient_id, treatment_id, teeth, status, progress) VALUES (?, ?, ?, '11', 'ACTIVE', ?)",
                    UUID.randomUUID(), p, treatment, row[1]);
        }

        List<Row> sorted = query.run(new Filter(practice, Kind.NO_VISIT_3M, null, null, null, true, "remaining"), now);
        assertThat(names(sorted)).containsExactly("Barely", "Half", "Almost");
        assertThat(sorted.get(0).remaining()).isEqualTo(90);

        assertThat(names(query.run(new Filter(practice, Kind.NO_VISIT_3M, null, 40, 60, true, "name"), now))).containsExactly("Half");
    }
}
