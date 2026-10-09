package com.orthoflow.patient.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.patient.application.port.InvoiceLinkGuard;
import com.orthoflow.patient.application.port.PatientErasureListener;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Erasing a patient must reach the data the database cascade does not: the
 * listeners run first, inside the same transaction, and a failing one stops the
 * erasure instead of leaving part of a person's data behind.
 */
class PatientErasureTest {

    private final UUID id = UUID.randomUUID();
    private PatientRepository patients;
    private InvoiceLinkGuard invoices;
    private PatientErasureListener voice;
    private PatientService service;

    @BeforeEach
    void setUp() {
        patients = mock(PatientRepository.class);
        invoices = mock(InvoiceLinkGuard.class);
        voice = mock(PatientErasureListener.class);
        when(patients.findById(id)).thenReturn(Optional.of(new Patient()));
        service = new PatientService(patients, invoices, List.of(voice), mock(PatientExtrasApplier.class), mock(CurrentUserProvider.class),
                com.orthoflow.testsupport.Tenants.fixed(java.util.UUID.randomUUID()));
    }

    @Test
    void listenersCleanUpBeforeThePatientRowIsDeleted() {
        service.erasePatient(id);

        InOrder order = inOrder(voice, patients);
        order.verify(voice).onPatientErased(id);
        order.verify(patients).deleteById(id);
    }

    @Test
    void aListenerThatFailsStopsTheErasure() {
        doThrow(new IllegalStateException("could not scrub")).when(voice).onPatientErased(id);

        assertThatThrownBy(() -> service.erasePatient(id)).isInstanceOf(IllegalStateException.class);

        verify(patients, never()).deleteById(id);
    }

    @Test
    void nothingIsScrubbedWhenInvoicesStillBlockTheErasure() {
        when(invoices.countInvoicesForPatient(id)).thenReturn(2L);

        assertThatThrownBy(() -> service.erasePatient(id)).isInstanceOf(ConflictException.class);

        verify(voice, never()).onPatientErased(id);
        verify(patients, never()).deleteById(id);
        assertThat(true).isTrue();
    }
}
