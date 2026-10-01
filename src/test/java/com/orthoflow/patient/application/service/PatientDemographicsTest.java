package com.orthoflow.patient.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.patient.application.dto.PatientDemographicsUpdate;
import com.orthoflow.patient.application.dto.PatientResponse;
import com.orthoflow.patient.application.port.InvoiceLinkGuard;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A consultation that learned one phone number must not wipe the address the
 * front desk typed — which is what routing it through the full-replace update
 * would do.
 */
class PatientDemographicsTest {

    private final UUID id = UUID.randomUUID();
    private PatientRepository patients;
    private PatientService service;
    private Patient patient;

    @BeforeEach
    void setUp() {
        patients = mock(PatientRepository.class);
        patient = Patient.builder().id(id).firstName("Karim").lastName("Alaoui").status("ACTIVE")
                .address("12 rue des Orangers").phone("0600000000").email("k@example.ma").build();
        when(patients.findById(id)).thenReturn(Optional.of(patient));
        when(patients.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new PatientService(patients, mock(InvoiceLinkGuard.class), List.of());
    }

    private static PatientDemographicsUpdate only(String phone, String cin) {
        return new PatientDemographicsUpdate(null, null, null, null, phone, cin, null, null);
    }

    @Test
    void changesOnlyWhatItIsGivenAndLeavesTheRestAlone() {
        PatientResponse updated = service.applyDemographics(id, new PatientDemographicsUpdate(
                null, null, LocalDate.of(1992, 4, 12), "m", "0612345678", "bk123456", "CNOPS", null));

        assertThat(updated.phone()).isEqualTo("0612345678");
        assertThat(updated.dateOfBirth()).isEqualTo(LocalDate.of(1992, 4, 12));
        assertThat(updated.gender()).isEqualTo("M");
        assertThat(updated.insuranceProvider()).isEqualTo("CNOPS");
        // untouched
        assertThat(updated.firstName()).isEqualTo("Karim");
        assertThat(updated.address()).isEqualTo("12 rue des Orangers");
        assertThat(updated.email()).isEqualTo("k@example.ma");
    }

    @Test
    void blankFieldsDoNotEraseWhatIsThere() {
        service.applyDemographics(id, new PatientDemographicsUpdate("  ", "", null, " ", " ", "", null, null));

        assertThat(patient.getFirstName()).isEqualTo("Karim");
        assertThat(patient.getPhone()).isEqualTo("0600000000");
    }

    @Test
    void anEmptyChangeWritesNothing() {
        service.applyDemographics(id, only(null, null));

        verify(patients, never()).save(any());
    }

    @Test
    void aCinHeldByADifferentPatientIsRefused() {
        when(patients.existsByCin("BK123456")).thenReturn(true);

        assertThatThrownBy(() -> service.applyDemographics(id, only(null, "BK123456")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("BK123456");
        verify(patients, never()).save(any());
    }

    @Test
    void thePatientsOwnCinIsNotAConflictWithThemselves() {
        patient.setCin("BK123456");
        when(patients.existsByCin("BK123456")).thenReturn(true);

        service.applyDemographics(id, only("0612345678", "bk123456"));

        assertThat(patient.getPhone()).isEqualTo("0612345678");
    }
}
