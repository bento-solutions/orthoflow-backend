package com.orthoflow.clinical.application.service;

import com.orthoflow.clinical.application.dto.PeriodontalStatusResponse;
import com.orthoflow.clinical.application.dto.RecordPeriodontalRequest;
import com.orthoflow.clinical.domain.model.PeriodontalAssessment;
import com.orthoflow.clinical.domain.repository.PeriodontalAssessmentRepository;
import com.orthoflow.common.exception.NotFoundException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeriodontalServiceTest {

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private final List<PeriodontalAssessment> rows = new ArrayList<>();
    private PeriodontalService service;

    @BeforeEach
    void setUp() {
        PeriodontalAssessmentRepository repo = mock(PeriodontalAssessmentRepository.class);
        PatientRepository patients = mock(PatientRepository.class);
        when(patients.findById(PATIENT)).thenReturn(Optional.of(mock(Patient.class)));
        when(repo.save(any())).thenAnswer(i -> {
            PeriodontalAssessment a = i.getArgument(0);
            a.prePersist();
            rows.add(a);
            return a;
        });
        // newest first, as the real query returns them
        when(repo.findByPatient(PATIENT)).thenAnswer(i -> rows.stream()
                .sorted(java.util.Comparator.comparing(PeriodontalAssessment::getAssessedOn)
                        .thenComparing(PeriodontalAssessment::getCreatedAt).reversed())
                .toList());
        service = new PeriodontalService(repo, patients);
    }

    private RecordPeriodontalRequest request(String region, String condition, LocalDate on) {
        RecordPeriodontalRequest r = new RecordPeriodontalRequest();
        r.setRegion(region);
        r.setCondition(condition);
        r.setAssessedOn(on);
        r.setSource("manual");
        return r;
    }

    @Test
    void theLatestAssessmentOfARegionIsItsCurrentState() {
        service.record(PATIENT, request("UPPER_FRONT", "GINGIVITIS", LocalDate.of(2025, 1, 10)), ACTOR);
        service.record(PATIENT, request("UPPER_FRONT", "HEALTHY", LocalDate.of(2025, 6, 10)), ACTOR);
        service.record(PATIENT, request("LOWER_LEFT", "PERIODONTITIS", LocalDate.of(2025, 3, 1)), ACTOR);

        PeriodontalStatusResponse status = service.status(PATIENT);

        assertThat(status.current()).extracting(a -> a.region() + ":" + a.condition())
                .containsExactlyInAnyOrder("UPPER_FRONT:HEALTHY", "LOWER_LEFT:PERIODONTITIS");
        assertThat(status.history()).hasSize(3);
    }

    @Test
    void aStageOnlyBelongsToPeriodontitis() {
        RecordPeriodontalRequest gingivitis = request("WHOLE_MOUTH", "GINGIVITIS", null);
        gingivitis.setStage(2);
        assertThatThrownBy(() -> service.record(PATIENT, gingivitis, ACTOR)).isInstanceOf(ValidationException.class);

        RecordPeriodontalRequest tooHigh = request("WHOLE_MOUTH", "PERIODONTITIS", null);
        tooHigh.setStage(5);
        assertThatThrownBy(() -> service.record(PATIENT, tooHigh, ACTOR)).isInstanceOf(ValidationException.class);

        RecordPeriodontalRequest ok = request("WHOLE_MOUTH", "PERIODONTITIS", null);
        ok.setStage(3);
        assertThat(service.record(PATIENT, ok, ACTOR).stage()).isEqualTo(3);
    }

    @Test
    void refusesUnknownRegionsFutureDatesAndUnknownPatients() {
        assertThatThrownBy(() -> service.record(PATIENT, request("MOUTH", "HEALTHY", null), ACTOR))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.record(PATIENT,
                request("WHOLE_MOUTH", "HEALTHY", LocalDate.now().plusDays(2)), ACTOR))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.status(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void assessmentDefaultsToToday() {
        assertThat(service.record(PATIENT, request("WHOLE_MOUTH", "HEALTHY", null), ACTOR).assessedOn())
                .isEqualTo(LocalDate.now());
    }
}
