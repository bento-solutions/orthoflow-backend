package com.orthoflow.clinical.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orthoflow.clinical.application.dto.ClinicalNoteResponse;
import com.orthoflow.clinical.application.dto.CreateClinicalNoteRequest;
import com.orthoflow.clinical.domain.model.ClinicalNote;
import com.orthoflow.clinical.domain.model.NoteCategory;
import com.orthoflow.clinical.domain.repository.ClinicalNoteRepository;
import com.orthoflow.clinical.domain.repository.DentalChartRepository;
import com.orthoflow.clinical.domain.repository.MedicalHistoryRepository;
import com.orthoflow.clinical.domain.repository.PatientAllergyRepository;
import com.orthoflow.clinical.domain.repository.ToothFindingRepository;
import com.orthoflow.clinical.domain.repository.ToothStateEventRepository;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A consultation save files several notes, and a save that fails part-way is
 * retried. A retry must find what it filed before, not file it twice.
 */
class ClinicalRecordServiceSessionNoteTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private final List<ClinicalNote> stored = new ArrayList<>();
    private ClinicalRecordService service;

    @BeforeEach
    void setUp() {
        ClinicalNoteRepository notes = mock(ClinicalNoteRepository.class);
        PatientRepository patients = mock(PatientRepository.class);
        when(patients.findById(PATIENT)).thenReturn(Optional.of(mock(Patient.class)));
        when(notes.save(any())).thenAnswer(invocation -> {
            ClinicalNote note = invocation.getArgument(0);
            if (note.getId() == null) note.prePersist();
            if (!stored.contains(note)) stored.add(note);
            return note;
        });
        when(notes.findBySession(any())).thenAnswer(invocation ->
                stored.stream().filter(n -> invocation.getArgument(0).equals(n.getSessionId())).toList());
        service = new ClinicalRecordService(mock(DentalChartRepository.class), mock(ToothFindingRepository.class),
                notes, mock(PatientAllergyRepository.class), mock(MedicalHistoryRepository.class),
                mock(ToothStateEventRepository.class), patients);
    }

    @Test
    void filesOneNotePerCategoryPerConsultationAndReplacesItOnRetry() {
        service.saveSessionNote(PATIENT, SESSION, NoteCategory.CONSULTATION_TRANSCRIPT, "première", ACTOR);
        service.saveSessionNote(PATIENT, SESSION, NoteCategory.TREATMENT_PLAN, "plan", ACTOR);
        ClinicalNoteResponse retried =
                service.saveSessionNote(PATIENT, SESSION, NoteCategory.CONSULTATION_TRANSCRIPT, "relue", ACTOR);

        assertThat(stored).hasSize(2);
        assertThat(retried.content()).isEqualTo("relue");
        assertThat(stored).extracting(ClinicalNote::getCategory)
                .containsExactlyInAnyOrder(NoteCategory.CONSULTATION_TRANSCRIPT, NoteCategory.TREATMENT_PLAN);
    }

    @Test
    void saysWhatProducedTheNoteSoTheRecordsProvenanceIsTrue() {
        service.saveSessionNote(PATIENT, SESSION, NoteCategory.TREATMENT_PLAN, "plan", ACTOR, "consultation");
        service.saveSessionNote(PATIENT, SESSION, NoteCategory.CONSULTATION_REPORT, "rapport", ACTOR);

        assertThat(stored).extracting(ClinicalNote::getSource).containsExactly("consultation", "voice");
    }

    @Test
    void refusesACategoryThatIsNotFiledPerConsultation() {
        assertThatThrownBy(() -> service.saveSessionNote(PATIENT, SESSION, NoteCategory.GENERAL, "x", ACTOR))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void refusesAnEmptyNote() {
        assertThatThrownBy(() -> service.saveSessionNote(PATIENT, SESSION, NoteCategory.TREATMENT_PLAN, "  ", ACTOR))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aTranscriptCannotBeTypedInByHandSoOneInTheRecordIsAlwaysARealOne() {
        CreateClinicalNoteRequest forged = new CreateClinicalNoteRequest();
        forged.setCategory("CONSULTATION_TRANSCRIPT");
        forged.setContent("Le patient a tout avoué.");
        forged.setSource("manual");

        assertThatThrownBy(() -> service.addNote(PATIENT, forged, ACTOR))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("recorded consultation");
        assertThat(stored).isEmpty();
    }
}
