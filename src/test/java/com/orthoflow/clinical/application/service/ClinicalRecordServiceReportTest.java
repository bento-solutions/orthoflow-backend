package com.orthoflow.clinical.application.service;

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
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The narrative a dentist signs at the end of a dictated examination, and
 * what makes it a record rather than a claim on a review page.
 */
class ClinicalRecordServiceReportTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private final List<ClinicalNote> stored = new ArrayList<>();
    private ClinicalRecordService service;
    private PatientRepository patients;

    @BeforeEach
    void setUp() {
        ClinicalNoteRepository notes = mock(ClinicalNoteRepository.class);
        patients = mock(PatientRepository.class);
        when(patients.findById(PATIENT)).thenReturn(Optional.of(mock(Patient.class)));
        when(notes.save(any())).thenAnswer(invocation -> {
            ClinicalNote note = invocation.getArgument(0);
            if (note.getId() == null) note.prePersist();
            if (!stored.contains(note)) stored.add(note);
            return note;
        });
        when(notes.findBySession(any())).thenAnswer(invocation ->
                stored.stream().filter(n -> SESSION.equals(n.getSessionId())).toList());

        service = new ClinicalRecordService(mock(DentalChartRepository.class), mock(ToothFindingRepository.class),
                notes, mock(PatientAllergyRepository.class), mock(MedicalHistoryRepository.class),
                mock(ToothStateEventRepository.class), patients);
    }

    @Test
    void filesTheNarrativeAsAConsultationReportNote() {
        ClinicalNoteResponse saved = service.saveConsultationReport(PATIENT, SESSION, "Dent 16 : carie profonde.", ACTOR);

        assertThat(saved.category()).isEqualTo("CONSULTATION_REPORT");
        assertThat(saved.content()).isEqualTo("Dent 16 : carie profonde.");
        assertThat(saved.source()).isEqualTo("voice");
        assertThat(saved.sessionId()).isEqualTo(SESSION);
        assertThat(saved.authorId()).isEqualTo(ACTOR);
    }

    @Test
    void savingTheSameExaminationAgainReplacesTheReportInsteadOfFilingItTwice() {
        service.saveConsultationReport(PATIENT, SESSION, "Première version.", ACTOR);

        ClinicalNoteResponse second = service.saveConsultationReport(PATIENT, SESSION, "Version relue.", ACTOR);

        assertThat(stored).hasSize(1);
        assertThat(second.content()).isEqualTo("Version relue.");
    }

    @Test
    void keepsAnotherExaminationsReportSeparate() {
        service.saveConsultationReport(PATIENT, SESSION, "Premier examen.", ACTOR);

        ClinicalNote other = ClinicalNote.builder().patientId(PATIENT).category(NoteCategory.CONSULTATION_REPORT)
                .content("Autre examen.").authorId(ACTOR).sessionId(UUID.randomUUID()).build();
        stored.add(other);
        service.saveConsultationReport(PATIENT, SESSION, "Premier examen, relu.", ACTOR);

        assertThat(other.getContent()).isEqualTo("Autre examen.");
        assertThat(stored).hasSize(2);
    }

    @Test
    void refusesAnEmptyReport() {
        assertThatThrownBy(() -> service.saveConsultationReport(PATIENT, SESSION, "  ", ACTOR))
                .isInstanceOf(ValidationException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    void refusesAnUnknownPatient() {
        UUID stranger = UUID.randomUUID();
        when(patients.findById(stranger)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveConsultationReport(stranger, SESSION, "Texte.", ACTOR))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aNoteCommandCannotForgeAReport() {
        CreateClinicalNoteRequest forged = new CreateClinicalNoteRequest();
        forged.setCategory("CONSULTATION_REPORT");
        forged.setContent("Compte rendu validé.");
        forged.setSource("manual");

        assertThatThrownBy(() -> service.addNote(PATIENT, forged, ACTOR))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("dictated examination");
        assertThat(stored).isEmpty();
    }
}
