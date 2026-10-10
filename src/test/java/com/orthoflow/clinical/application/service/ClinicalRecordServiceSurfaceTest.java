package com.orthoflow.clinical.application.service;

import com.orthoflow.clinical.application.dto.AddToothFindingRequest;
import com.orthoflow.clinical.application.dto.ToothFindingResponse;
import com.orthoflow.clinical.domain.model.ChartType;
import com.orthoflow.clinical.domain.model.DentalChart;
import com.orthoflow.clinical.domain.model.FindingOrigin;
import com.orthoflow.clinical.domain.model.ToothFinding;
import com.orthoflow.clinical.domain.repository.ClinicalNoteRepository;
import com.orthoflow.clinical.domain.repository.DentalChartRepository;
import com.orthoflow.clinical.domain.repository.MedicalHistoryRepository;
import com.orthoflow.clinical.domain.repository.PatientAllergyRepository;
import com.orthoflow.clinical.domain.repository.ToothFindingRepository;
import com.orthoflow.clinical.domain.repository.ToothStateEventRepository;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tooth carries several findings at once, on different surfaces, and a
 * finding remembers when and by whom it was done.
 */
class ClinicalRecordServiceSurfaceTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private ToothFindingRepository findings;
    private ClinicalRecordService service;
    private final List<ToothFinding> store = new ArrayList<>();

    @BeforeEach
    void setUp() {
        findings = mock(ToothFindingRepository.class);
        DentalChartRepository charts = mock(DentalChartRepository.class);
        PatientRepository patients = mock(PatientRepository.class);
        DentalChart chart = DentalChart.builder().id(UUID.randomUUID()).patientId(PATIENT).chartType(ChartType.adult).build();
        when(patients.findById(PATIENT)).thenReturn(Optional.of(mock(Patient.class)));
        when(charts.findByPatientId(PATIENT)).thenReturn(Optional.of(chart));
        when(charts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(findings.findActiveByChartAndFdi(any(), any())).thenAnswer(i -> List.copyOf(store));
        when(findings.findActiveByChartFdiCodeAndSurface(any(), any(), any(), any())).thenAnswer(i -> {
            String code = i.getArgument(2);
            String surface = i.getArgument(3);
            return store.stream()
                    .filter(f -> f.getFindingCode().equals(code) && java.util.Objects.equals(f.getSurface(), surface))
                    .findFirst();
        });
        when(findings.save(any())).thenAnswer(i -> {
            ToothFinding finding = i.getArgument(0);
            if (finding.getId() == null) finding.prePersist();
            if (!store.contains(finding)) store.add(finding);
            return finding;
        });
        service = new ClinicalRecordService(charts, findings, mock(ClinicalNoteRepository.class),
                mock(PatientAllergyRepository.class), mock(MedicalHistoryRepository.class),
                mock(ToothStateEventRepository.class), patients);
    }

    private AddToothFindingRequest request(String code, String surface) {
        AddToothFindingRequest request = new AddToothFindingRequest();
        request.setFindingCode(code);
        request.setSurface(surface);
        request.setSource("manual");
        return request;
    }

    @Test
    void theSameFindingOnTwoSurfacesIsTwoLesions() {
        service.addFinding(PATIENT, "16", request("caries", "mesial"), ACTOR);
        service.addFinding(PATIENT, "16", request("caries", "distal"), ACTOR);

        assertThat(store).extracting(ToothFinding::getSurface).containsExactlyInAnyOrder("mesial", "distal");
    }

    @Test
    void aCariesAndAnAmalgamCoexistOnOneTooth() {
        service.addFinding(PATIENT, "16", request("caries", "mesial"), ACTOR);
        service.addFinding(PATIENT, "16", request("existing_amalgam", "occlusal"), ACTOR);

        assertThat(store).extracting(ToothFinding::getFindingCode)
                .containsExactlyInAnyOrder("caries", "existing_amalgam");
    }

    @Test
    void repeatingTheSameSurfaceUpdatesInsteadOfDuplicating() {
        service.addFinding(PATIENT, "16", request("caries", "mesial"), ACTOR);
        service.addFinding(PATIENT, "16", request("caries", "mesial"), ACTOR);

        assertThat(store).hasSize(1);
    }

    @Test
    void sayingWhereABareFindingIsRefinesIt() {
        service.addFinding(PATIENT, "16", request("caries", null), ACTOR);
        service.addFinding(PATIENT, "16", request("caries", "mesial-occlusal"), ACTOR);

        assertThat(store).hasSize(1);
        assertThat(store.get(0).getSurface()).isEqualTo("mesial-occlusal");
    }

    @Test
    void recordsWhenAndWhereExternalWorkWasDone() {
        AddToothFindingRequest request = request("existing_amalgam", "occlusal");
        request.setPerformedOn(LocalDate.of(2019, 4, 2));
        request.setOrigin("EXTERNAL");
        request.setProviderName("Dr Benani, Casablanca");

        ToothFindingResponse response = service.addFinding(PATIENT, "26", request, ACTOR);

        assertThat(response.performedOn()).isEqualTo(LocalDate.of(2019, 4, 2));
        assertThat(response.origin()).isEqualTo("EXTERNAL");
        assertThat(response.providerName()).isEqualTo("Dr Benani, Casablanca");
    }

    @Test
    void workIsThisClinicsUnlessSaidOtherwise() {
        ToothFindingResponse response = service.addFinding(PATIENT, "26", request("caries", null), ACTOR);

        assertThat(response.origin()).isEqualTo(FindingOrigin.THIS_CLINIC.name());
        assertThat(response.performedOn()).isNull();
    }

    @Test
    void aRepeatedDictationDoesNotEraseAnEnteredDate() {
        AddToothFindingRequest first = request("caries", "mesial");
        first.setPerformedOn(LocalDate.of(2024, 1, 5));
        service.addFinding(PATIENT, "16", first, ACTOR);

        ToothFindingResponse again = service.addFinding(PATIENT, "16", request("caries", "mesial"), ACTOR);

        assertThat(again.performedOn()).isEqualTo(LocalDate.of(2024, 1, 5));
    }

    @Test
    void aFutureDateOrAnUnknownOriginIsRefused() {
        AddToothFindingRequest future = request("caries", null);
        future.setPerformedOn(LocalDate.now().plusDays(1));
        assertThatThrownBy(() -> service.addFinding(PATIENT, "16", future, ACTOR))
                .isInstanceOf(ValidationException.class);

        AddToothFindingRequest odd = request("caries", null);
        odd.setOrigin("ELSEWHERE");
        assertThatThrownBy(() -> service.addFinding(PATIENT, "16", odd, ACTOR))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void theHistoryReadsNewestFirstByTheDateTheWorkWasDone() {
        AddToothFindingRequest old = request("existing_amalgam", "occlusal");
        old.setPerformedOn(LocalDate.of(2015, 3, 1));
        old.setOrigin("EXTERNAL");
        AddToothFindingRequest recent = request("existing_crown", null);
        recent.setPerformedOn(LocalDate.of(2024, 9, 12));
        service.addFinding(PATIENT, "26", old, ACTOR);
        service.addFinding(PATIENT, "36", recent, ACTOR);
        service.addFinding(PATIENT, "46", request("caries", null), ACTOR); // date unknown: its record date, today
        when(findings.findHistoryByChart(any())).thenAnswer(i -> List.copyOf(store));

        List<ToothFindingResponse> history = service.listFindingHistory(PATIENT);

        assertThat(history).extracting(ToothFindingResponse::fdi).containsExactly("46", "36", "26");
    }
}
