package com.orthoflow.consultation;

import static org.assertj.core.api.Assertions.assertThat;

import com.orthoflow.consultation.application.dto.ConsultationLogResponse;
import com.orthoflow.consultation.application.dto.ConsultationResponse;
import com.orthoflow.consultation.application.dto.StartConsultationRequest;
import com.orthoflow.consultation.application.dto.TranscriptRequest;
import com.orthoflow.consultation.application.service.ConsultationLogService;
import com.orthoflow.consultation.application.service.ConsultationService;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The consultation log against the real schema: the migration, the entity's
 * mapping ({@code ddl-auto: validate}), the tenant column, and the erasure that
 * has to leave nothing behind when a consultation is thrown away.
 */
@TestPropertySource(properties = "orthoflow.consultation.enabled=true")
class ConsultationLogDbTest extends SpringDbTest {

    @Autowired
    private ConsultationService consultations;
    @Autowired
    private ConsultationLogService logs;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID user;
    private UUID patient;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'Nabil', 'Farrak', 'DOCTOR', ?)",
                user, user + "@x.ma", practice);
        signInAs(user, practice);
        patient = PostgresTestSupport.patient(jdbc, practice, "Mohamed", "El Adawy", "1995-03-01", null, null);
    }

    private ConsultationResponse started() {
        StartConsultationRequest request = new StartConsultationRequest();
        request.setPatientId(patient);
        request.setLocale("fr-MA");
        request.setPatientInformed(true);
        return consultations.start(request, user);
    }

    private static TranscriptRequest.Line line(int seq, OffsetDateTime at, String text, String phase) {
        TranscriptRequest.Line line = new TranscriptRequest.Line();
        line.setSeq(seq);
        line.setAt(at);
        line.setText(text);
        line.setPhase(phase);
        return line;
    }

    @Test
    void theLinesComeBackInOrderWithTheirTimesAndAreNotDuplicatedByAResend() {
        ConsultationResponse c = started();
        OffsetDateTime t = c.startedAt().plusSeconds(5);
        List<TranscriptRequest.Line> sent = List.of(
                line(0, t, "Bonjour docteur", "INTAKE"),
                line(1, t.plusSeconds(12), "Dent 16, carie", "EXAMINATION"));

        logs.record(c.id(), sent);
        logs.record(c.id(), sent);

        ConsultationLogResponse log = logs.log(c.id());
        assertThat(log.lines()).extracting(ConsultationLogResponse.Line::text)
                .containsExactly("Bonjour docteur", "Dent 16, carie");
        assertThat(log.lines()).extracting(ConsultationLogResponse.Line::phase).containsExactly("INTAKE", "EXAMINATION");
        // Postgres keeps microseconds; a Linux clock gives nanoseconds.
        assertThat(log.lines().get(1).at().toInstant())
                .isEqualTo(t.plusSeconds(12).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(jdbc.queryForObject("SELECT practice_id FROM consultation_transcript_lines WHERE consultation_id = ? AND seq = 0",
                UUID.class, c.id())).isEqualTo(practice);
    }

    @Test
    void eachLineKeepsItsVerdictAndALateVerdictFillsItInOnce() {
        ConsultationResponse c = started();
        OffsetDateTime t = c.startedAt().plusSeconds(5);
        TranscriptRequest.Line acted = line(0, t, "Dent 16, carie", "EXAMINATION");
        acted.setVerdict("acted");
        acted.setLatencyMs(420);
        TranscriptRequest.Line heard = line(1, t.plusSeconds(4), "Oui docteur", "EXAMINATION");
        logs.record(c.id(), List.of(acted, heard));

        TranscriptRequest.Line late = line(1, t.plusSeconds(4), "Oui docteur", "EXAMINATION");
        late.setVerdict("heard");
        logs.record(c.id(), List.of(late));
        TranscriptRequest.Line rewrite = line(0, t, "Dent 16, carie", "EXAMINATION");
        rewrite.setVerdict("unsure");
        logs.record(c.id(), List.of(rewrite));

        List<ConsultationLogResponse.Line> lines = logs.log(c.id()).lines();
        assertThat(lines).extracting(ConsultationLogResponse.Line::verdict).containsExactly("acted", "heard");
        assertThat(lines.get(0).latencyMs()).isEqualTo(420);
    }

    @Test
    void discardingAConsultationLeavesNoLineBehind() {
        ConsultationResponse c = started();
        logs.record(c.id(), List.of(line(0, null, "à ne pas garder", null)));
        assertThat(count(c.id())).isEqualTo(1);

        consultations.abandon(c.id());

        assertThat(count(c.id())).isZero();
    }

    @Test
    void erasingThePatientTakesTheLogWithIt() {
        ConsultationResponse c = started();
        logs.record(c.id(), List.of(line(0, null, "dossier", null)));

        jdbc.update("DELETE FROM voice_command_audit WHERE patient_id = ?", patient);
        jdbc.update("DELETE FROM patients WHERE id = ?", patient);

        assertThat(count(c.id())).isZero();
    }

    private int count(UUID consultation) {
        return jdbc.queryForObject("SELECT count(*) FROM consultation_transcript_lines WHERE consultation_id = ?",
                Integer.class, consultation);
    }
}
