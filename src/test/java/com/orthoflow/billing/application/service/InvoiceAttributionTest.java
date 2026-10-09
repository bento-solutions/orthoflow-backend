package com.orthoflow.billing.application.service;

import com.orthoflow.billing.application.dto.CreateInvoiceRequest;
import com.orthoflow.billing.application.dto.InvoiceLineRequest;
import com.orthoflow.billing.application.port.InvoiceAttributionGuard;
import com.orthoflow.billing.domain.model.Invoice;
import com.orthoflow.billing.domain.model.InvoiceStatus;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import com.orthoflow.billing.domain.repository.PaymentRepository;
import com.orthoflow.billing.infrastructure.adapter.persistence.InvoiceAuditLogJpaRepository;
import com.orthoflow.billing.infrastructure.adapter.persistence.ReceiptJpaRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.team.application.service.PractitionerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Whose invoice it is decides whose retrocession it counts toward, so who is stamped
 * on a new invoice, and when that may change, is covered here.
 */
class InvoiceAttributionTest {

    private final UUID practice = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();

    private InvoiceRepository invoices;
    private PractitionerService practitioners;
    private PatientLookup patients;
    private InvoiceAttributionGuard guard;
    private BillingService billing;

    @BeforeEach
    void setUp() {
        invoices = mock(InvoiceRepository.class);
        practitioners = mock(PractitionerService.class);
        patients = mock(PatientLookup.class);
        guard = mock(InvoiceAttributionGuard.class);
        com.orthoflow.billing.application.service.InvoiceNumberGenerator numbers = mock(InvoiceNumberGenerator.class);
        when(numbers.generate(any())).thenReturn("INV-2026-MA-00001");
        when(invoices.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        billing = new BillingService(invoices, mock(PaymentRepository.class), numbers, mock(InvoiceAuditLogJpaRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), mock(ReceiptJpaRepository.class), practitioners, patients, guard, com.orthoflow.testsupport.Tenants.fixed(practice));
        when(patients.findPrimaryPractitionerId(any())).thenReturn(Optional.empty());
        when(practitioners.findIdByUser(any())).thenReturn(Optional.empty());
    }

    private CreateInvoiceRequest request(UUID practitioner) {
        InvoiceLineRequest line = new InvoiceLineRequest();
        line.setActCode("ACT");
        line.setLabel("Visit");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(BigDecimal.TEN);
        line.setDiscountPct(BigDecimal.ZERO);
        line.setSortOrder(0);
        CreateInvoiceRequest r = new CreateInvoiceRequest();
        r.setPracticeId(practice);
        r.setPatientId(patient);
        r.setCurrency("MAD");
        r.setRegionCode("MA");
        r.setPractitionerId(practitioner);
        r.setLines(List.of(line));
        return r;
    }

    private UUID created(CreateInvoiceRequest r) {
        return billing.createInvoice(r, creator).getPractitionerId();
    }

    @Test
    void anExplicitPractitionerIsCheckedAndUsed() {
        UUID dr = UUID.randomUUID();
        assertThat(created(request(dr))).isEqualTo(dr);
        verify(practitioners).require(practice, dr);
    }

    @Test
    void withoutOneTheInvoiceGoesToThePatientsPrimaryPractitioner() {
        UUID primary = UUID.randomUUID();
        when(patients.findPrimaryPractitionerId(patient)).thenReturn(Optional.of(primary));
        when(practitioners.findIdByUser(creator)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(created(request(null))).isEqualTo(primary);
    }

    @Test
    void failingThatItGoesToTheDoctorWhoIsBillingIt() {
        UUID self = UUID.randomUUID();
        when(practitioners.findIdByUser(creator)).thenReturn(Optional.of(self));

        assertThat(created(request(null))).isEqualTo(self);
    }

    @Test
    void failingThatItStaysUnattributedForSomeoneToAssign() {
        assertThat(created(request(null))).isNull();
    }

    private Invoice stored(UUID practitioner) {
        Invoice invoice = Invoice.builder().id(UUID.randomUUID()).practiceId(practice).patientId(patient).invoiceNumber("INV-1")
                .status(InvoiceStatus.PAID).practitionerId(practitioner).total(BigDecimal.TEN).subtotal(BigDecimal.TEN)
                .currency("MAD").regionCode("MA").createdBy(creator).build();
        when(invoices.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    void reassigningAnInvoiceAsksTheGuardAndThenMovesIt() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        Invoice invoice = stored(from);

        billing.assignPractitioner(invoice.getId(), to, creator);

        verify(guard).assertReassignable(invoice.getId());
        ArgumentCaptor<Invoice> saved = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).save(saved.capture());
        assertThat(saved.getValue().getPractitionerId()).isEqualTo(to);
    }

    @Test
    void aVetoFromTheGuardLeavesTheInvoiceAlone() {
        Invoice invoice = stored(UUID.randomUUID());
        doThrow(new ConflictException("counted on a statement")).when(guard).assertReassignable(invoice.getId());

        assertThatThrownBy(() -> billing.assignPractitioner(invoice.getId(), UUID.randomUUID(), creator)).isInstanceOf(ConflictException.class);
        verify(invoices, never()).save(any());
    }

    @Test
    void assigningTheSamePractitionerAgainIsANoOp() {
        UUID dr = UUID.randomUUID();
        Invoice invoice = stored(dr);

        billing.assignPractitioner(invoice.getId(), dr, creator);

        verifyNoInteractions(guard);
        verify(invoices, never()).save(any());
    }

    @Test
    void anInvoiceCanBeLeftUnattributed() {
        Invoice invoice = stored(UUID.randomUUID());

        billing.assignPractitioner(invoice.getId(), null, creator);

        assertThat(invoice.getPractitionerId()).isNull();
    }
}
