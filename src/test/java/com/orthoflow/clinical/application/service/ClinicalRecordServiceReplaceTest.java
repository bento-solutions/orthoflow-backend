package com.orthoflow.clinical.application.service;

import com.orthoflow.clinical.application.dto.AddToothFindingRequest;
import com.orthoflow.clinical.domain.model.ChartType;
import com.orthoflow.clinical.domain.model.DentalChart;
import com.orthoflow.clinical.domain.model.FindingKind;
import com.orthoflow.clinical.domain.model.FindingStatus;
import com.orthoflow.clinical.domain.model.ToothFinding;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The correction path, "no, actually crown replacement": what it withdraws,
 * what it records, and — the part that matters — that nothing is withdrawn
 * unless the replacement can be recorded.
 */
class ClinicalRecordServiceReplaceTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private ToothFindingRepository findings;
    private ToothStateEventRepository events;
    private ClinicalRecordService service;
    private DentalChart chart;
    private final List<ToothFinding> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        findings = mock(ToothFindingRepository.class);
        DentalChartRepository charts = mock(DentalChartRepository.class);
        PatientRepository patients = mock(PatientRepository.class);

        chart = DentalChart.builder().id(UUID.randomUUID()).patientId(PATIENT).chartType(ChartType.adult).build();
        when(patients.findById(PATIENT)).thenReturn(Optional.of(mock(Patient.class)));
        when(charts.findByPatientId(PATIENT)).thenReturn(Optional.of(chart));
        when(charts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(findings.findActiveByChartFdiAndCode(any(), any(), any())).thenReturn(Optional.empty());
        when(findings.findActiveByChartAndFdi(any(), any())).thenReturn(List.of());
        when(findings.save(any())).thenAnswer(invocation -> {
            ToothFinding finding = invocation.getArgument(0);
            if (finding.getId() == null) finding.prePersist();
            saved.add(finding);
            return finding;
        });

        events = mock(ToothStateEventRepository.class);
        service = new ClinicalRecordService(charts, findings, mock(ClinicalNoteRepository.class),
                mock(PatientAllergyRepository.class), mock(MedicalHistoryRepository.class),
                events, patients);
    }

    private ToothFinding existing(String fdi, String code, UUID patientOfChart) {
        DentalChart owner = DentalChart.builder().id(UUID.randomUUID()).patientId(patientOfChart).build();
        ToothFinding finding = ToothFinding.builder()
                .id(UUID.randomUUID()).chart(owner).fdi(fdi).findingCode(code)
                .kind(FindingKind.CONDITION).recordedBy(ACTOR).build();
        when(findings.findById(finding.getId())).thenReturn(Optional.of(finding));
        return finding;
    }

    private AddToothFindingRequest request(String code) {
        AddToothFindingRequest request = new AddToothFindingRequest();
        request.setFindingCode(code);
        request.setSource("voice");
        return request;
    }

    @Test
    void withdrawsTheSupersededFindingAndRecordsTheReplacement() {
        ToothFinding old = existing("16", "caries", PATIENT);

        var result = service.replaceFindings(PATIENT, "16", List.of(old.getId()),
                List.of(request("crown_replacement_required")), ACTOR);

        assertThat(old.getStatus()).isEqualTo(FindingStatus.RETRACTED);
        assertThat(result.retracted()).extracting(r -> r.findingCode()).containsExactly("caries");
        assertThat(result.added()).extracting(r -> r.findingCode()).containsExactly("crown_replacement_required");
    }

    @Test
    void anUnknownReplacementCodeWithdrawsNothing() {
        ToothFinding old = existing("16", "caries", PATIENT);

        assertThatThrownBy(() -> service.replaceFindings(PATIENT, "16", List.of(old.getId()),
                List.of(request("not_a_finding")), ACTOR))
                .isInstanceOf(ValidationException.class);

        assertThat(old.getStatus()).isEqualTo(FindingStatus.ACTIVE);
        verify(findings, never()).save(any());
    }

    @Test
    void anInvalidSeverityWithdrawsNothing() {
        ToothFinding old = existing("16", "caries", PATIENT);
        AddToothFindingRequest bad = request("caries");
        bad.setSeverity("CATASTROPHIC");

        assertThatThrownBy(() -> service.replaceFindings(PATIENT, "16", List.of(old.getId()), List.of(bad), ACTOR))
                .isInstanceOf(ValidationException.class);

        assertThat(old.getStatus()).isEqualTo(FindingStatus.ACTIVE);
    }

    @Test
    void aFindingOfAnotherPatientIsNotWithdrawn() {
        ToothFinding foreign = existing("16", "caries", UUID.randomUUID());

        assertThatThrownBy(() -> service.replaceFindings(PATIENT, "16", List.of(foreign.getId()),
                List.of(request("crown_replacement_required")), ACTOR))
                .isInstanceOf(NotFoundException.class);

        assertThat(foreign.getStatus()).isEqualTo(FindingStatus.ACTIVE);
        verify(findings, never()).save(any());
    }

    @Test
    void aFindingOnAnotherToothIsNotWithdrawn() {
        ToothFinding elsewhere = existing("26", "caries", PATIENT);

        assertThatThrownBy(() -> service.replaceFindings(PATIENT, "16", List.of(elsewhere.getId()),
                List.of(request("crown_replacement_required")), ACTOR))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("tooth 26");

        assertThat(elsewhere.getStatus()).isEqualTo(FindingStatus.ACTIVE);
        verify(findings, never()).save(any());
    }

    @Test
    void aStaleIdIsNotFoundRatherThanIgnored() {
        UUID gone = UUID.randomUUID();
        when(findings.findById(gone)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceFindings(PATIENT, "16", List.of(gone),
                List.of(request("crown_replacement_required")), ACTOR))
                .isInstanceOf(NotFoundException.class);

        verify(findings, never()).save(any());
    }

    // ── Who the tooth audit trail says made the change ──────────────────

    private String sourceOfRecordedChange() {
        org.mockito.ArgumentCaptor<com.orthoflow.clinical.domain.model.ToothStateEvent> event =
                org.mockito.ArgumentCaptor.forClass(com.orthoflow.clinical.domain.model.ToothStateEvent.class);
        verify(events).save(event.capture());
        return event.getValue().getSource();
    }

    @Test
    void aFindingWithdrawnFromTheChartScreenIsLoggedAsManual() {
        ToothFinding finding = existing("16", "caries", PATIENT);

        service.changeFindingStatus(PATIENT, finding.getId(), FindingStatus.RETRACTED, ACTOR);

        // It used to say "voice" for every status change, whoever made it.
        assertThat(sourceOfRecordedChange()).isEqualTo("manual");
    }

    @Test
    void aFindingWithdrawnByAVoiceCommandIsLoggedAsVoice() {
        ToothFinding finding = existing("16", "caries", PATIENT);

        service.changeFindingStatus(finding.getId(), FindingStatus.RETRACTED, ACTOR);

        assertThat(sourceOfRecordedChange()).isEqualTo("voice");
    }

    @Test
    void aVoiceCorrectionIsLoggedAsVoice() {
        ToothFinding old = existing("16", "caries", PATIENT);

        service.replaceFindings(PATIENT, "16", List.of(old.getId()),
                List.of(request("crown_replacement_required")), ACTOR);

        org.mockito.ArgumentCaptor<com.orthoflow.clinical.domain.model.ToothStateEvent> event =
                org.mockito.ArgumentCaptor.forClass(com.orthoflow.clinical.domain.model.ToothStateEvent.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).save(event.capture());
        assertThat(event.getAllValues()).allMatch(e -> "voice".equals(e.getSource()));
    }

    // ── Surfaces ────────────────────────────────────────────────────────

    private AddToothFindingRequest withSurface(String code, String surface) {
        AddToothFindingRequest request = request(code);
        request.setSurface(surface);
        return request;
    }

    @Test
    void aCompoundSurfaceIsStoredWhole() {
        ToothFinding old = existing("16", "caries", PATIENT);

        service.replaceFindings(PATIENT, "16", List.of(old.getId()),
                List.of(withSurface("caries", "Mesial-Occlusal")), ACTOR);

        assertThat(saved.stream().filter(f -> f != old).map(ToothFinding::getSurface))
                .containsExactly("mesial-occlusal");
    }

    @Test
    void aSurfaceThatIsNotOneOrMoreFacesWithdrawsNothing() {
        ToothFinding old = existing("16", "caries", PATIENT);

        for (String bad : List.of("mesial occlusal", "mesial-occlusal-distal-buccal", "occlusal;DROP", "12", "-")) {
            assertThatThrownBy(() -> service.replaceFindings(PATIENT, "16", List.of(old.getId()),
                    List.of(withSurface("caries", bad)), ACTOR))
                    .as(bad).isInstanceOf(ValidationException.class);
        }
        assertThat(old.getStatus()).isEqualTo(FindingStatus.ACTIVE);
    }
}
