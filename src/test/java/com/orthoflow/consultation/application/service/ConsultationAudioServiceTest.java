package com.orthoflow.consultation.application.service;

import static com.orthoflow.consultation.application.service.ConsultationFixtures.ACTOR;
import static com.orthoflow.consultation.application.service.ConsultationFixtures.consultation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.consultation.application.service.ConsultationFixtures.InMemoryConsultations;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** The recording kept beside the timestamped transcript, and the rules that let it go with it. */
class ConsultationAudioServiceTest {

    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0};

    private InMemoryConsultations repo;
    private ConsultationExtractionProperties properties;
    private FileService files;
    private ConsultationAudioService audio;
    private Consultation consultation;

    @BeforeEach
    void setUp() {
        repo = new InMemoryConsultations();
        properties = new ConsultationExtractionProperties();
        properties.setEnabled(true);
        files = mock(FileService.class);
        ConsultationService service = new ConsultationService(repo, null, null, null, null, null, properties,
                new ObjectMapper(), mock(ConsultationLogService.class), files);
        audio = new ConsultationAudioService(repo, service, properties, files);
        consultation = repo.save(consultation(ConsultationStatus.EXAMINATION));
        when(files.storeAudio(any(), eq(FileOwnerType.CONSULTATION_AUDIO), any(), any(), any(), anyLong(), any()))
                .thenAnswer(call -> StoredFile.builder().id(UUID.randomUUID()).contentType("audio/webm")
                        .sizeBytes(WEBM.length).originalName(call.getArgument(3)).build());
    }

    private static MockMultipartFile upload() {
        return new MockMultipartFile("file", "consultation.webm", "audio/webm", WEBM);
    }

    @Test
    void keepsTheRecordingWithWhenItStartedAndHowLongItIs() {
        OffsetDateTime started = consultation.getStartedAt().plusSeconds(2);

        audio.store(consultation.getId(), upload(), started, 754_000L, ACTOR);

        Consultation after = repo.findById(consultation.getId()).orElseThrow();
        assertThat(after.getAudioFileId()).isNotNull();
        assertThat(after.getAudioStartedAt()).isEqualTo(started);
        assertThat(after.getAudioDurationMs()).isEqualTo(754_000L);
        verify(files).storeAudio(eq(consultation.getPracticeId()), eq(FileOwnerType.CONSULTATION_AUDIO), eq(consultation.getId()),
                org.mockito.ArgumentMatchers.argThat((String name) -> name.startsWith("consultation-") && name.endsWith(".webm")),
                eq(WEBM), eq(100L * 1024 * 1024), eq(ACTOR));
    }

    @Test
    void sendingItAgainReplacesTheOldOne() {
        audio.store(consultation.getId(), upload(), null, null, ACTOR);
        UUID first = repo.findById(consultation.getId()).orElseThrow().getAudioFileId();

        audio.store(consultation.getId(), upload(), null, null, ACTOR);

        verify(files).delete(consultation.getPracticeId(), first);
        assertThat(repo.findById(consultation.getId()).orElseThrow().getAudioFileId()).isNotEqualTo(first);
    }

    @Test
    void aClinicThatDoesNotKeepTheConversationDoesNotKeepItsAudio() {
        properties.setRetainTranscript(false);

        assertThatThrownBy(() -> audio.store(consultation.getId(), upload(), null, null, ACTOR))
                .isInstanceOf(ValidationException.class);
        verify(files, never()).storeAudio(any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void aDiscardedConsultationTakesNoRecording() {
        consultation.setStatus(ConsultationStatus.ABANDONED);

        assertThatThrownBy(() -> audio.store(consultation.getId(), upload(), null, null, ACTOR))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void anImpossibleDurationIsNotBelieved() {
        audio.store(consultation.getId(), upload(), null, 90L * 24 * 60 * 60 * 1000, ACTOR);

        assertThat(repo.findById(consultation.getId()).orElseThrow().getAudioDurationMs()).isNull();
    }

    @Test
    void theDoctorCanRemoveTheRecordingAndKeepTheRest() {
        audio.store(consultation.getId(), upload(), null, null, ACTOR);
        UUID kept = repo.findById(consultation.getId()).orElseThrow().getAudioFileId();

        audio.delete(consultation.getId());

        verify(files).delete(consultation.getPracticeId(), kept);
        assertThat(repo.findById(consultation.getId()).orElseThrow().getAudioFileId()).isNull();
    }
}
