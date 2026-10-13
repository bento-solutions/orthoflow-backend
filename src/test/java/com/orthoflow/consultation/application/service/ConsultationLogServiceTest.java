package com.orthoflow.consultation.application.service;

import static com.orthoflow.consultation.application.service.ConsultationFixtures.VOICE_SESSION;
import static com.orthoflow.consultation.application.service.ConsultationFixtures.consultation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.consultation.application.dto.ConsultationLogResponse;
import com.orthoflow.consultation.application.dto.TranscriptRequest;
import com.orthoflow.consultation.application.service.ConsultationFixtures.InMemoryConsultations;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.domain.model.ConsultationTranscriptLine;
import com.orthoflow.consultation.domain.repository.ConsultationLineRepository;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.voice.application.dto.VoiceCommandAuditResponse;
import com.orthoflow.voice.application.service.VoiceAuditService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsultationLogServiceTest {

    private InMemoryConsultations repo;
    private InMemoryLines lines;
    private VoiceAuditService voiceAudit;
    private ConsultationExtractionProperties properties;
    private ConsultationLogService service;
    private Consultation consultation;

    @BeforeEach
    void setUp() {
        repo = new InMemoryConsultations();
        lines = new InMemoryLines();
        voiceAudit = mock(VoiceAuditService.class);
        properties = new ConsultationExtractionProperties();
        properties.setEnabled(true);
        properties.setTimezone("Africa/Casablanca");
        service = new ConsultationLogService(repo, lines, voiceAudit, properties, new ObjectMapper().findAndRegisterModules());
        consultation = repo.save(consultation(ConsultationStatus.INTAKE));
    }

    private static TranscriptRequest.Line line(int seq, OffsetDateTime at, String text) {
        TranscriptRequest.Line line = new TranscriptRequest.Line();
        line.setSeq(seq);
        line.setAt(at);
        line.setText(text);
        return line;
    }

    @Test
    void storesEachLineWithTheTimeItWasHeard() {
        OffsetDateTime heard = consultation.getStartedAt().plusSeconds(30);

        service.record(consultation.getId(), List.of(line(0, heard, "Bonjour docteur"), line(1, heard.plusSeconds(5), "J'ai mal à la 16")));

        assertThat(lines.rows).extracting(ConsultationTranscriptLine::getText)
                .containsExactly("Bonjour docteur", "J'ai mal à la 16");
        assertThat(lines.rows.get(0).getSpokenAt()).isEqualTo(heard);
        assertThat(lines.rows.get(0).getPhase()).isEqualTo(ConsultationTranscriptLine.Phase.INTAKE);
    }

    @Test
    void resendingTheWholeConversationDoesNotStoreALineTwice() {
        OffsetDateTime heard = consultation.getStartedAt().plusSeconds(10);
        service.record(consultation.getId(), List.of(line(0, heard, "Bonjour")));

        service.record(consultation.getId(), List.of(line(0, heard, "Bonjour"), line(1, heard.plusSeconds(4), "Merci")));

        assertThat(lines.rows).extracting(ConsultationTranscriptLine::getSeq).containsExactly(0, 1);
    }

    @Test
    void aLineAlreadyStoredIsNeverRewrittenByALaterResend() {
        OffsetDateTime heard = consultation.getStartedAt().plusSeconds(10);
        service.record(consultation.getId(), List.of(line(0, heard, "Bonjour")));

        service.record(consultation.getId(), List.of(line(0, heard.plusSeconds(99), "Bonsoir")));

        assertThat(lines.rows).hasSize(1);
        assertThat(lines.rows.get(0).getText()).isEqualTo("Bonjour");
        assertThat(lines.rows.get(0).getSpokenAt()).isEqualTo(heard);
    }

    @Test
    void aClockThatCannotBeRightIsHeldToTheConsultationsOwnSpan() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime started = consultation.getStartedAt();

        assertThat(ConsultationLogService.believable(started.minusHours(3), started, now)).isEqualTo(started);
        assertThat(ConsultationLogService.believable(now.plusDays(2), started, now)).isEqualTo(now);
        assertThat(ConsultationLogService.believable(null, started, now)).isEqualTo(now);
        OffsetDateTime fine = started.plusMinutes(3);
        assertThat(ConsultationLogService.believable(fine, started, now.plusMinutes(10))).isEqualTo(fine);
    }

    @Test
    void aClosedConsultationTakesNoMoreLines() {
        Consultation done = repo.save(consultation(ConsultationStatus.COMPLETED));

        service.record(done.getId(), List.of(line(0, OffsetDateTime.now(), "trop tard")));

        assertThat(lines.rows).isEmpty();
    }

    @Test
    void nothingIsRecordedWhileTheFeatureIsOff() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.record(consultation.getId(), List.of(line(0, null, "x"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theLogPutsTheLinesTheCommandsAndWhatWasValidatedTogether() {
        OffsetDateTime heard = consultation.getStartedAt().plusSeconds(20);
        service.record(consultation.getId(), List.of(line(0, heard, "dent 16 carie")));
        consultation.setReviewed("{\"report\":\"relu\"}");
        when(voiceAudit.forSession(VOICE_SESSION)).thenReturn(List.of(
                VoiceCommandAuditResponse.builder().id(UUID.randomUUID()).intent("chart.finding")
                        .occurredAt(heard.plusSeconds(8)).outcome("EXECUTED").build(),
                VoiceCommandAuditResponse.builder().id(UUID.randomUUID()).intent("chart.select")
                        .occurredAt(heard.plusSeconds(1)).outcome("EXECUTED").build()));

        ConsultationLogResponse log = service.log(consultation.getId());

        assertThat(log.lines()).hasSize(1);
        assertThat(log.lines().get(0).at()).isEqualTo(heard);
        assertThat(log.commands()).extracting(VoiceCommandAuditResponse::intent)
                .containsExactly("chart.select", "chart.finding");
        assertThat(log.reviewed().get("report").asText()).isEqualTo("relu");
        assertThat(log.transcriptKept()).isTrue();
    }

    @Test
    void aConsultationRecordedBeforeLinesWereTimedStillShowsItsTextWithoutTimes() {
        consultation.setTranscript("Bonjour\nJ'ai mal\n");

        ConsultationLogResponse log = service.log(consultation.getId());

        assertThat(log.lines()).extracting(ConsultationLogResponse.Line::text).containsExactly("Bonjour", "J'ai mal");
        assertThat(log.lines()).allMatch(l -> l.at() == null);
    }

    @Test
    void theNoteFiledOnTheRecordLeadsEachLineWithTheClinicTime() {
        // Shown in the clinic's own time zone; UTC here so the expectation does not move with Morocco's clock.
        OffsetDateTime heard = consultation.getStartedAt().atZoneSameInstant(ZoneOffset.UTC)
                .withHour(9).withMinute(15).withSecond(30).toOffsetDateTime();
        lines.rows.add(ConsultationTranscriptLine.builder().consultationId(consultation.getId()).seq(0).spokenAt(heard)
                .phase(ConsultationTranscriptLine.Phase.INTAKE).text("Bonjour").build());
        lines.rows.add(ConsultationTranscriptLine.builder().consultationId(consultation.getId()).seq(1).spokenAt(heard.plusSeconds(4))
                .phase(ConsultationTranscriptLine.Phase.EXAMINATION).typed(true).text("RDV dans 15 jours").build());
        properties.setTimezone("UTC");

        assertThat(service.timestampedTranscript(consultation))
                .isEqualTo("[09:15:30] Bonjour\n[09:15:34] (saisi) RDV dans 15 jours");
    }

    @Test
    void erasingRemovesTheLines() {
        service.record(consultation.getId(), List.of(line(0, null, "à effacer")));

        service.erase(consultation.getId());

        assertThat(lines.rows).isEmpty();
    }

    /** Lines in memory, in the order stored. */
    private static final class InMemoryLines implements ConsultationLineRepository {
        final List<ConsultationTranscriptLine> rows = new ArrayList<>();

        @Override
        public void saveAll(Collection<ConsultationTranscriptLine> toSave) {
            rows.addAll(toSave);
            rows.sort(Comparator.comparingInt(ConsultationTranscriptLine::getSeq));
        }

        @Override
        public List<ConsultationTranscriptLine> findByConsultation(UUID consultationId) {
            return rows.stream().filter(l -> l.getConsultationId().equals(consultationId)).toList();
        }

        @Override
        public Set<Integer> seqsOf(UUID consultationId) {
            Set<Integer> seqs = new HashSet<>();
            findByConsultation(consultationId).forEach(l -> seqs.add(l.getSeq()));
            return seqs;
        }

        @Override
        public void deleteByConsultation(UUID consultationId) {
            rows.removeIf(l -> l.getConsultationId().equals(consultationId));
        }
    }
}
