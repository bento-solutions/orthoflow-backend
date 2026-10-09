package com.orthoflow.treatment.application.service;

import com.orthoflow.billing.application.dto.CreateInvoiceRequest;
import com.orthoflow.billing.application.dto.InvoiceResponse;
import com.orthoflow.billing.application.service.BillingService;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.inventory.application.port.ConsumableLedger;
import com.orthoflow.treatment.domain.model.*;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.treatment.domain.repository.TreatmentInvoiceRepository;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Protects the ADR 0005 / audit I.3 / P3 #39 invariant: finalizing a
 * treatment session must create exactly one linked billing.Invoice with a
 * single treatment-fee line — the actual patient-facing financial document
 * — while stock.TreatmentInvoice itself stays a cost/consumption record.
 */
@ExtendWith(MockitoExtension.class)
class TreatmentInvoiceServiceTest {

    @Mock private TreatmentInvoiceRepository treatmentInvoiceRepository;
    @Mock private PatientLookup patientLookup;
    @Mock private TreatmentRepository treatmentRepository;
    @Mock private StockItemRepository stockItemRepository;
    @Mock private ConsumableLedger consumableLedger;
    @Mock private BillingService billingService;
    @Mock private JdbcTemplate jdbcTemplate;

    private TreatmentInvoiceService service;

    @BeforeEach
    void setUp() {
        service = new TreatmentInvoiceService(
                treatmentInvoiceRepository, patientLookup, treatmentRepository,
                stockItemRepository, consumableLedger, billingService, jdbcTemplate, mock(com.orthoflow.common.numbering.DocumentNumbers.class));
    }

    private TreatmentInvoice draftInvoice(BigDecimal treatmentPrice) {
        Treatment treatment = Treatment.builder()
                .id(UUID.randomUUID())
                .code("TR-001")
                .name("Orthodontic Adjustment")
                .basePrice(treatmentPrice)
                .build();

        return TreatmentInvoice.builder()
                .id(UUID.randomUUID())
                .invoiceNumber("TINV-2026-0001")
                .patientId(UUID.randomUUID())
                .treatment(treatment)
                .status(TreatmentInvoiceStatus.DRAFT)
                .treatmentPrice(treatmentPrice)
                .consumablesUsed(new java.util.ArrayList<>())
                .discounts(new java.util.ArrayList<>())
                .createdBy(UUID.randomUUID())
                .build();
    }

    @Test
    void finalizeInvoice_createsLinkedBillingInvoice_withTreatmentFeeOnlyLine() {
        TreatmentInvoice invoice = draftInvoice(BigDecimal.valueOf(500));
        when(treatmentInvoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(treatmentInvoiceRepository.save(any(TreatmentInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID billingInvoiceId = UUID.randomUUID();
        when(billingService.createInvoice(any(CreateInvoiceRequest.class), any(UUID.class)))
                .thenReturn(InvoiceResponse.builder().id(billingInvoiceId).build());

        UUID finalizedBy = UUID.randomUUID();
        TreatmentInvoice result = service.finalizeInvoice(invoice.getId(), finalizedBy);

        assertThat(result.getStatus()).isEqualTo(TreatmentInvoiceStatus.FINALIZED);
        assertThat(result.getBillingInvoiceId()).isEqualTo(billingInvoiceId);

        ArgumentCaptor<CreateInvoiceRequest> captor = ArgumentCaptor.forClass(CreateInvoiceRequest.class);
        verify(billingService).createInvoice(captor.capture(), eq(finalizedBy));
        CreateInvoiceRequest request = captor.getValue();

        assertThat(request.getPatientId()).isEqualTo(invoice.getPatientId());
        assertThat(request.getLines()).hasSize(1);
        assertThat(request.getLines().get(0).getUnitPrice()).isEqualByComparingTo("500");
        assertThat(request.getLines().get(0).getQuantity()).isEqualByComparingTo("1");
        assertThat(request.getLines().get(0).getLabel()).isEqualTo("Orthodontic Adjustment");
    }

    @Test
    void patientChargeAmount_appliesTreatmentLevelDiscount_excludesConsumablesCost() {
        TreatmentInvoice invoice = draftInvoice(BigDecimal.valueOf(1000));
        invoice.addDiscount(InvoiceDiscount.builder()
                .target(DiscountTarget.TREATMENT)
                .type(DiscountType.PERCENTAGE)
                .value(BigDecimal.valueOf(10))
                .build());

        assertThat(invoice.patientChargeAmount()).isEqualByComparingTo("900");
    }

    @Test
    void cancelInvoice_voidsLinkedBillingInvoice_whenOneExists() {
        TreatmentInvoice invoice = draftInvoice(BigDecimal.valueOf(500));
        invoice.setStatus(TreatmentInvoiceStatus.FINALIZED);
        UUID billingInvoiceId = UUID.randomUUID();
        invoice.setBillingInvoiceId(billingInvoiceId);

        when(treatmentInvoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(treatmentInvoiceRepository.save(any(TreatmentInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID cancelledBy = UUID.randomUUID();
        TreatmentInvoice result = service.cancelInvoice(invoice.getId(), cancelledBy);

        assertThat(result.getStatus()).isEqualTo(TreatmentInvoiceStatus.CANCELLED);
        verify(billingService).cancelInvoice(billingInvoiceId, cancelledBy);
    }

    @Test
    void cancelInvoice_doesNotTouchBilling_whenNeverFinalized() {
        TreatmentInvoice invoice = draftInvoice(BigDecimal.valueOf(500));
        when(treatmentInvoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(treatmentInvoiceRepository.save(any(TreatmentInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancelInvoice(invoice.getId(), UUID.randomUUID());

        verify(billingService, never()).cancelInvoice(any(), any());
    }
}
