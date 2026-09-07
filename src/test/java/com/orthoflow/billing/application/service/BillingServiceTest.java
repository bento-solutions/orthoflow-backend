package com.orthoflow.billing.application.service;

import com.orthoflow.billing.application.dto.CreateInvoiceRequest;
import com.orthoflow.billing.application.dto.InvoiceLineRequest;
import com.orthoflow.billing.application.dto.InvoiceResponse;
import com.orthoflow.billing.application.dto.RecordPaymentRequest;
import com.orthoflow.billing.domain.model.*;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import com.orthoflow.billing.domain.repository.PaymentRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Protects the money invariants audit II.1/II.14/III.8 called out: a line
 * total can never go negative, a discount is tracked separately from the
 * subtotal it is subtracted from, and a payment can never push a balance
 * past zero or land on an invoice that is already settled or cancelled.
 */
@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private InvoiceNumberGenerator invoiceNumberGenerator;
    @Mock
    private com.orthoflow.billing.infrastructure.adapter.persistence.InvoiceAuditLogJpaRepository auditLogRepository;

    private BillingService billingService;

    @BeforeEach
    void setUp() {
        billingService = new BillingService(invoiceRepository, paymentRepository, invoiceNumberGenerator,
                auditLogRepository, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private void stubInvoiceCreation() {
        when(invoiceNumberGenerator.generate(any())).thenReturn("INV-2026-MA-00001");
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateInvoiceRequest requestWithLine(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPct) {
        InvoiceLineRequest line = new InvoiceLineRequest();
        line.setActCode("ACT-1");
        line.setLabel("Consultation");
        line.setQuantity(quantity);
        line.setUnitPrice(unitPrice);
        line.setDiscountPct(discountPct);
        line.setSortOrder(0);

        CreateInvoiceRequest request = new CreateInvoiceRequest();
        request.setPracticeId(UUID.randomUUID());
        request.setPatientId(UUID.randomUUID());
        request.setCurrency("MAD");
        request.setRegionCode("MA");
        request.setLines(List.of(line));
        return request;
    }

    @Test
    void createInvoice_withValidLines_calculatesTotalAsSumOfLines() {
        stubInvoiceCreation();
        CreateInvoiceRequest request = requestWithLine(
                BigDecimal.valueOf(2), BigDecimal.valueOf(100), BigDecimal.ZERO);

        InvoiceResponse response = billingService.createInvoice(request, UUID.randomUUID());

        assertThat(response.getSubtotal()).isEqualByComparingTo("200.00");
        assertThat(response.getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(response.getTotal()).isEqualByComparingTo("200.00");
    }

    @Test
    void createInvoice_withDiscount_neverProducesNegativeLineTotal() {
        stubInvoiceCreation();
        // 100% discount is the maximum the DTO validation allows; the
        // resulting line total must floor at zero, never go negative.
        CreateInvoiceRequest request = requestWithLine(
                BigDecimal.valueOf(1), BigDecimal.valueOf(50), BigDecimal.valueOf(100));

        InvoiceResponse response = billingService.createInvoice(request, UUID.randomUUID());

        assertThat(response.getSubtotal()).isEqualByComparingTo("50.00");
        assertThat(response.getDiscountAmount()).isEqualByComparingTo("50.00");
        assertThat(response.getTotal()).isEqualByComparingTo("0.00");
        assertThat(response.getLines().get(0).getLineTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createInvoice_discountIsTrackedSeparatelyFromSubtotal() {
        stubInvoiceCreation();
        // audit III.8: subtotal must stay the pre-discount gross figure,
        // with discountAmount reported on its own line — not baked in.
        CreateInvoiceRequest request = requestWithLine(
                BigDecimal.valueOf(1), BigDecimal.valueOf(200), BigDecimal.valueOf(10));

        InvoiceResponse response = billingService.createInvoice(request, UUID.randomUUID());

        assertThat(response.getSubtotal()).isEqualByComparingTo("200.00");
        assertThat(response.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(response.getTotal()).isEqualByComparingTo("180.00");
    }

    @Test
    void invoiceTotal_matchesSumOfLineItems_forMultipleLines() {
        stubInvoiceCreation();
        InvoiceLineRequest line1 = new InvoiceLineRequest();
        line1.setActCode("A1"); line1.setLabel("L1");
        line1.setQuantity(BigDecimal.valueOf(3)); line1.setUnitPrice(BigDecimal.valueOf(40));
        line1.setDiscountPct(BigDecimal.ZERO); line1.setSortOrder(0);

        InvoiceLineRequest line2 = new InvoiceLineRequest();
        line2.setActCode("A2"); line2.setLabel("L2");
        line2.setQuantity(BigDecimal.valueOf(1)); line2.setUnitPrice(BigDecimal.valueOf(60));
        line2.setDiscountPct(BigDecimal.ZERO); line2.setSortOrder(1);

        CreateInvoiceRequest request = new CreateInvoiceRequest();
        request.setPracticeId(UUID.randomUUID());
        request.setPatientId(UUID.randomUUID());
        request.setCurrency("MAD");
        request.setRegionCode("MA"); // 0% tax rate, keeps the assertion simple
        request.setLines(List.of(line1, line2));

        InvoiceResponse response = billingService.createInvoice(request, UUID.randomUUID());

        BigDecimal expectedTotal = response.getLines().stream()
                .map(l -> l.getLineTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(response.getTotal()).isEqualByComparingTo(expectedTotal);
        assertThat(response.getTotal()).isEqualByComparingTo("180.00");
    }

    @Test
    void recordPayment_partialPayment_cannotExceedOutstandingBalance() {
        Invoice invoice = Invoice.builder()
                .id(UUID.randomUUID())
                .status(InvoiceStatus.SENT)
                .total(BigDecimal.valueOf(100))
                .payments(new java.util.ArrayList<>())
                .build();
        when(invoiceRepository.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));

        RecordPaymentRequest request = new RecordPaymentRequest();
        request.setAmount(BigDecimal.valueOf(150));
        request.setMethod(PaymentMethod.CASH);
        request.setPaymentDate(LocalDate.now());

        assertThatThrownBy(() -> billingService.recordPayment(invoice.getId(), request, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("exceeds the outstanding balance");
    }

    @Test
    void recordPayment_onAlreadyPaidInvoice_isRejected() {
        Invoice invoice = Invoice.builder()
                .id(UUID.randomUUID())
                .status(InvoiceStatus.PAID)
                .total(BigDecimal.valueOf(100))
                .payments(new java.util.ArrayList<>())
                .build();
        when(invoiceRepository.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));

        RecordPaymentRequest request = new RecordPaymentRequest();
        request.setAmount(BigDecimal.valueOf(10));
        request.setMethod(PaymentMethod.CASH);
        request.setPaymentDate(LocalDate.now());

        assertThatThrownBy(() -> billingService.recordPayment(invoice.getId(), request, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already fully paid");
    }

    @Test
    void recordPayment_onCancelledInvoice_isRejected() {
        Invoice invoice = Invoice.builder()
                .id(UUID.randomUUID())
                .status(InvoiceStatus.CANCELLED)
                .total(BigDecimal.valueOf(100))
                .payments(new java.util.ArrayList<>())
                .build();
        when(invoiceRepository.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));

        RecordPaymentRequest request = new RecordPaymentRequest();
        request.setAmount(BigDecimal.valueOf(10));
        request.setMethod(PaymentMethod.CASH);
        request.setPaymentDate(LocalDate.now());

        assertThatThrownBy(() -> billingService.recordPayment(invoice.getId(), request, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void recordPayment_exactRemainingBalance_marksInvoicePaid() {
        Invoice invoice = Invoice.builder()
                .id(UUID.randomUUID())
                .status(InvoiceStatus.SENT)
                .total(BigDecimal.valueOf(100))
                .payments(new java.util.ArrayList<>())
                .build();
        when(invoiceRepository.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));

        RecordPaymentRequest request = new RecordPaymentRequest();
        request.setAmount(BigDecimal.valueOf(100));
        request.setMethod(PaymentMethod.CASH);
        request.setPaymentDate(LocalDate.now());

        billingService.recordPayment(invoice.getId(), request, UUID.randomUUID());

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void getInvoice_forUnknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(invoiceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> billingService.getInvoice(id))
                .isInstanceOf(NotFoundException.class);
    }
}
