package com.orthoflow.treatment.application.service;

import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.billing.application.service.BillingService;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.treatment.application.dto.InvoiceDiscountRequest;
import com.orthoflow.treatment.application.dto.TreatmentInvoiceConsumableRequest;
import com.orthoflow.treatment.application.dto.TreatmentInvoiceRequest;
import com.orthoflow.inventory.domain.model.*;
import com.orthoflow.treatment.domain.model.*;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.treatment.domain.repository.TreatmentInvoiceRepository;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.orthoflow.inventory.application.port.ConsumableLedger;
import com.orthoflow.inventory.domain.model.SourceType;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.treatment.domain.model.InvoiceDiscount;
import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.model.TreatmentConsumable;
import com.orthoflow.treatment.domain.model.TreatmentInvoice;
import com.orthoflow.treatment.domain.model.TreatmentInvoiceConsumable;
import com.orthoflow.treatment.domain.model.TreatmentInvoiceStatus;

@Service
@RequiredArgsConstructor
@Slf4j
public class TreatmentInvoiceService {

    /**
     * Matches the placeholder the frontend already sends on every
     * billing.Invoice it creates (invoice-create.component.ts) — there is
     * no practices table and no tenancy enforcement yet (ADR
     * 0002-tenancy-deferred), so this is vestigial by design, not a
     * shortcut taken here specifically.
     */
    private static final String DEFAULT_CURRENCY = "MAD";
    private static final String DEFAULT_REGION_CODE = "MA";

    private final TreatmentInvoiceRepository treatmentInvoiceRepository;
    private final PatientLookup patientLookup;
    private final TreatmentRepository treatmentRepository;
    private final StockItemRepository stockItemRepository;
    private final ConsumableLedger consumableLedger;
    private final BillingService billingService;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentNumbers documentNumbers;

    public List<TreatmentInvoice> getAllInvoices() {
        return enrichWithPatients(treatmentInvoiceRepository.findAll());
    }

    public Optional<TreatmentInvoice> getInvoiceById(UUID id) {
        return treatmentInvoiceRepository.findById(id).map(this::enrichWithPatient);
    }

    public Optional<TreatmentInvoice> getInvoiceByNumber(String invoiceNumber) {
        return treatmentInvoiceRepository.findByInvoiceNumber(invoiceNumber).map(this::enrichWithPatient);
    }

    public List<TreatmentInvoice> getInvoicesByPatient(UUID patientId) {
        return enrichWithPatients(treatmentInvoiceRepository.findByPatientId(patientId));
    }

    /** Attaches the display-only PatientSummary this entity no longer carries as a JPA relation. */
    private TreatmentInvoice enrichWithPatient(TreatmentInvoice invoice) {
        invoice.setPatient(patientLookup.findSummary(invoice.getPatientId()).orElse(null));
        return invoice;
    }

    private List<TreatmentInvoice> enrichWithPatients(List<TreatmentInvoice> invoices) {
        List<UUID> patientIds = invoices.stream().map(TreatmentInvoice::getPatientId).distinct().toList();
        Map<UUID, PatientSummary> summaries = patientIds.isEmpty()
                ? Collections.emptyMap()
                : patientLookup.findSummaries(patientIds);
        invoices.forEach(i -> i.setPatient(summaries.get(i.getPatientId())));
        return invoices;
    }

    @Transactional
    public TreatmentInvoice createDraftFromTreatment(UUID patientId, UUID treatmentId, UUID createdBy) {
        return createDraftFromTreatment(patientId, treatmentId, createdBy, null);
    }

    /** {@code surface} prices the treatment by the faces it covers (see {@link com.orthoflow.treatment.domain.model.SurfacePricing}). */
    @Transactional
    public TreatmentInvoice createDraftFromTreatment(UUID patientId, UUID treatmentId, UUID createdBy, String surface) {
        PatientSummary patient = patientLookup.findSummary(patientId)
                .orElseThrow(() -> new IllegalArgumentException("Patient not found: " + patientId));
        Treatment treatment = treatmentRepository.findById(treatmentId)
                .orElseThrow(() -> new IllegalArgumentException("Treatment not found: " + treatmentId));

        TreatmentInvoice invoice = TreatmentInvoice.builder()
                .invoiceNumber(generateInvoiceNumber())
                .patientId(patientId)
                .treatment(treatment)
                .sessionDate(LocalDate.now())
                .status(TreatmentInvoiceStatus.DRAFT)
                .treatmentPrice(com.orthoflow.treatment.domain.model.SurfacePricing
                        .quote(treatment.getBasePrice(), treatment.getSurfacePrices(), surface).price())
                .createdBy(java.util.Objects.requireNonNull(createdBy, "createdBy is required"))
                .build();

        // Add default consumables configured for the treatment
        if (treatment.getConsumables() != null) {
            for (TreatmentConsumable defaultConsumable : treatment.getConsumables()) {
                TreatmentInvoiceConsumable line = TreatmentInvoiceConsumable.builder()
                        .treatmentInvoice(invoice)
                        .stockItem(defaultConsumable.getStockItem())
                        .defaultQuantity(defaultConsumable.getQuantityUsed())
                        .actualQuantity(defaultConsumable.getQuantityUsed())
                        .pricePerUnit(defaultConsumable.getStockItem().getPricePerUse())
                        .modified(false)
                        .build();
                line.calculateTotalCost();
                invoice.addConsumable(line);
            }
        }

        invoice.calculateTotal();
        TreatmentInvoice saved = treatmentInvoiceRepository.save(invoice);
        saved.setPatient(patient);
        return saved;
    }

    /**
     * Upsert for the "save draft" / "edit draft" flow — {@code request.getId()}
     * null means create, present means edit an existing DRAFT (there is no
     * path {id} on this endpoint; the frontend embeds it in the body).
     *
     * `status`, `stockMovementsGenerated`, `createdBy`, `invoiceNumber`,
     * `finalizedAt` are always server-owned and are never read from the
     * request DTO — on update they are simply left untouched on the fetched,
     * managed entity. Binding the entity directly (the previous behaviour)
     * let a client POST `status: "FINALIZED"` straight through this
     * endpoint, skipping the stock validation/deduction and audit trail
     * that {@code finalizeInvoice} performs (see audit V.6, exploit A / I.5).
     *
     * Each consumable line's `pricePerUnit` is likewise always recomputed
     * here from the stock item's current `pricePerUse` rather than trusted
     * from the client — it fed directly into `calculateTotal()`, so an
     * arbitrary client-supplied value was a price-tampering vector.
     */
    @Transactional
    public TreatmentInvoice saveInvoice(TreatmentInvoiceRequest request, UUID actorId) {
        TreatmentInvoice invoice;
        if (request.getId() == null) {
            invoice = TreatmentInvoice.builder()
                    .invoiceNumber(generateInvoiceNumber())
                    .status(TreatmentInvoiceStatus.DRAFT)
                    .createdBy(java.util.Objects.requireNonNull(actorId, "createdBy is required"))
                    .build();
        } else {
            invoice = treatmentInvoiceRepository.findById(request.getId())
                    .orElseThrow(() -> new NotFoundException("Invoice not found: " + request.getId()));
        }

        UUID patientId = request.getPatient().getId();
        if (!patientLookup.exists(patientId)) {
            throw new NotFoundException("Patient not found: " + patientId);
        }
        invoice.setPatientId(patientId);

        Treatment treatment = treatmentRepository.findById(request.getTreatment().getId())
                .orElseThrow(() -> new NotFoundException("Treatment not found: " + request.getTreatment().getId()));
        invoice.setTreatment(treatment);

        if (request.getSessionDate() != null) {
            invoice.setSessionDate(request.getSessionDate());
        }
        invoice.setTreatmentPrice(request.getTreatmentPrice());
        invoice.setNotes(request.getNotes());

        invoice.getConsumablesUsed().clear();
        if (request.getConsumablesUsed() != null) {
            for (TreatmentInvoiceConsumableRequest lineRequest : request.getConsumablesUsed()) {
                StockItem stockItem = stockItemRepository.findById(lineRequest.getStockItem().getId())
                        .orElseThrow(() -> new NotFoundException("Stock item not found: " + lineRequest.getStockItem().getId()));
                BigDecimal defaultQuantity = lineRequest.getDefaultQuantity() != null
                        ? lineRequest.getDefaultQuantity()
                        : lineRequest.getActualQuantity();
                TreatmentInvoiceConsumable line = TreatmentInvoiceConsumable.builder()
                        .stockItem(stockItem)
                        .defaultQuantity(defaultQuantity)
                        .actualQuantity(lineRequest.getActualQuantity())
                        .pricePerUnit(stockItem.getPricePerUse())
                        .build();
                invoice.addConsumable(line);
            }
        }

        invoice.getDiscounts().clear();
        if (request.getDiscounts() != null) {
            for (InvoiceDiscountRequest discountRequest : request.getDiscounts()) {
                InvoiceDiscount discount = InvoiceDiscount.builder()
                        .type(discountRequest.getType())
                        .target(discountRequest.getTarget())
                        .targetId(discountRequest.getTargetId())
                        .value(discountRequest.getValue())
                        .reason(discountRequest.getReason())
                        .build();
                invoice.addDiscount(discount);
            }
        }

        invoice.calculateTotal();
        return enrichWithPatient(treatmentInvoiceRepository.save(invoice));
    }

    @Transactional
    public TreatmentInvoice finalizeInvoice(UUID id, UUID finalizedBy) {
        TreatmentInvoice invoice = enrichWithPatient(treatmentInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + id)));

        if (invoice.getStatus() != TreatmentInvoiceStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT invoices can be finalized.");
        }

        // Validate stock levels and decimal support before finalizing
        if (invoice.getConsumablesUsed() != null) {
            for (TreatmentInvoiceConsumable line : invoice.getConsumablesUsed()) {
                BigDecimal qtyUsed = line.getActualQuantity();
                if (qtyUsed != null && qtyUsed.compareTo(BigDecimal.ZERO) > 0) {
                    StockItem stockItem = stockItemRepository.findById(line.getStockItem().getId())
                            .orElseThrow(() -> new IllegalArgumentException("Stock item not found: " + line.getStockItem().getId()));

                    // 1. Decimal validation: check if decimal is not supported but qty has a fractional part
                    if (!stockItem.isDecimalSupported()) {
                        if (qtyUsed.stripTrailingZeros().scale() > 0) {
                            throw new IllegalArgumentException("Decimal quantity not supported for item: " + stockItem.getName() +
                                    " (Provided: " + qtyUsed + "). Only whole numbers are allowed.");
                        }
                    }

                    // 2. Stock level validation
                    BigDecimal currentStock = stockItem.getCurrentStock() != null ? stockItem.getCurrentStock() : BigDecimal.ZERO;
                    if (currentStock.compareTo(qtyUsed) < 0) {
                        throw new IllegalArgumentException("Insufficient stock for item: " + stockItem.getName() +
                                " (Available: " + currentStock + ", Required: " + qtyUsed + ")");
                    }
                }
            }
        }

        invoice.setStatus(TreatmentInvoiceStatus.FINALIZED);
        invoice.setFinalizedAt(OffsetDateTime.now());

        // Deduct consumables stock and record movements
        if (!invoice.isStockMovementsGenerated() && invoice.getConsumablesUsed() != null) {
            for (TreatmentInvoiceConsumable line : invoice.getConsumablesUsed()) {
                BigDecimal qtyUsed = line.getActualQuantity();
                if (qtyUsed != null && qtyUsed.compareTo(BigDecimal.ZERO) > 0) {
                    consumableLedger.consume(
                            line.getStockItem().getId(),
                            qtyUsed,
                            SourceType.TREATMENT_INVOICE,
                            invoice.getId(),
                            invoice.getInvoiceNumber(),
                            "Deducted for treatment session (" + invoice.getTreatment().getName() + ")",
                            finalizedBy
                    );
                }
            }
            invoice.setStockMovementsGenerated(true);
        }

        // Create the actual patient-facing financial document (ADR 0005 /
        // audit I.3 / P3 #39) — this record stays a cost/consumption record,
        // it is not itself the invoice. One line for the treatment fee only
        // (see TreatmentInvoice#patientChargeAmount for what that excludes).
        // Going-forward only, by design: this does not run for sessions that
        // were already FINALIZED before this existed.
        com.orthoflow.billing.application.dto.InvoiceLineRequest line =
                new com.orthoflow.billing.application.dto.InvoiceLineRequest();
        line.setActCode(invoice.getTreatment().getCode());
        line.setLabel(invoice.getTreatment().getName());
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(invoice.patientChargeAmount());
        line.setDiscountPct(BigDecimal.ZERO);
        line.setSortOrder(0);

        com.orthoflow.billing.application.dto.CreateInvoiceRequest invoiceRequest =
                new com.orthoflow.billing.application.dto.CreateInvoiceRequest();
        invoiceRequest.setPatientId(invoice.getPatientId());
        invoiceRequest.setCurrency(DEFAULT_CURRENCY);
        invoiceRequest.setRegionCode(DEFAULT_REGION_CODE);
        invoiceRequest.setNotes("Treatment session " + invoice.getInvoiceNumber());
        invoiceRequest.setLines(List.of(line));

        var billingInvoice = billingService.createInvoice(invoiceRequest, finalizedBy);
        invoice.setBillingInvoiceId(billingInvoice.getId());

        return treatmentInvoiceRepository.save(invoice);
    }

    @Transactional
    public TreatmentInvoice cancelInvoice(UUID id, UUID cancelledBy) {
        TreatmentInvoice invoice = enrichWithPatient(treatmentInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + id)));

        if (invoice.getStatus() == TreatmentInvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Invoice is already cancelled.");
        }

        // Restore stock if movements were previously generated
        if (invoice.isStockMovementsGenerated() && invoice.getConsumablesUsed() != null) {
            for (TreatmentInvoiceConsumable line : invoice.getConsumablesUsed()) {
                BigDecimal qtyUsed = line.getActualQuantity();
                if (qtyUsed != null && qtyUsed.compareTo(BigDecimal.ZERO) > 0) {
                    consumableLedger.restore(
                            line.getStockItem().getId(),
                            qtyUsed,
                            SourceType.TREATMENT_INVOICE,
                            invoice.getId(),
                            invoice.getInvoiceNumber(),
                            "Stock restored due to Treatment Invoice cancellation: " + invoice.getInvoiceNumber(),
                            cancelledBy
                    );
                }
            }
            invoice.setStockMovementsGenerated(false);
        }

        // Void the linked patient-facing invoice too (ADR 0005 / P3 #39) —
        // otherwise the patient is still billed for a session that was just
        // reversed. billingService.cancelInvoice refuses (throws, rolling
        // back this whole transaction) if a payment was already recorded
        // against it — cancelling a treatment session after the patient
        // has paid needs a refund first, not a silent void.
        if (invoice.getBillingInvoiceId() != null) {
            billingService.cancelInvoice(invoice.getBillingInvoiceId(), cancelledBy);
        }

        invoice.setStatus(TreatmentInvoiceStatus.CANCELLED);
        return treatmentInvoiceRepository.save(invoice);
    }

    @Transactional
    public void deleteInvoice(UUID id) {
        TreatmentInvoice invoice = treatmentInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + id));
        if (invoice.getStatus() == TreatmentInvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Finalized invoices cannot be deleted.");
        }
        treatmentInvoiceRepository.deleteById(id);
    }

    private String generateInvoiceNumber() {
        Long nextVal = documentNumbers.next("treatment_invoice");
        return "TINV-" + LocalDate.now().getYear() + "-" + String.format("%04d", nextVal);
    }
}
