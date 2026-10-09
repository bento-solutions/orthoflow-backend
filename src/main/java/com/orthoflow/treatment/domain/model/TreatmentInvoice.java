package com.orthoflow.treatment.domain.model;

import org.hibernate.annotations.TenantId;
import com.orthoflow.patient.application.port.PatientSummary;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "treatment_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentInvoice {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "invoice_number", unique = true, nullable = false)
    private String invoiceNumber;

    // A plain UUID rather than a @ManyToOne Patient (audit I.2 / docs/adr/0001-patient-in-billing.md).
    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /** Not persisted — see the identical field on SalesOrder for why. */
    @Transient
    @Builder.Default
    private PatientSummary patient = null;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "treatment_id", nullable = false)
    private Treatment treatment;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TreatmentInvoiceStatus status = TreatmentInvoiceStatus.DRAFT;

    @OneToMany(mappedBy = "treatmentInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TreatmentInvoiceConsumable> consumablesUsed = new ArrayList<>();

    @OneToMany(mappedBy = "treatmentInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<InvoiceDiscount> discounts = new ArrayList<>();

    @Column(name = "treatment_price", nullable = false)
    private BigDecimal treatmentPrice;

    @Column(name = "consumables_cost", nullable = false)
    @Builder.Default
    private BigDecimal consumablesCost = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "stock_movements_generated", nullable = false)
    @Builder.Default
    private boolean stockMovementsGenerated = false;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finalized_at")
    private OffsetDateTime finalizedAt;

    /**
     * The actual patient-facing financial document created when this
     * session is finalized (ADR 0005 / audit I.3 / P3 #39) — this record
     * stays a cost/consumption record for inventory and profitability
     * analytics, it is not itself an invoice. Null for sessions finalized
     * before this link existed, and for DRAFT/CANCELLED sessions that never
     * reached finalization.
     */
    @Column(name = "billing_invoice_id")
    private UUID billingInvoiceId;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (sessionDate == null) {
            sessionDate = LocalDate.now();
        }
        calculateTotal();
    }

    @PreUpdate
    public void preUpdate() {
        calculateTotal();
    }

    public void calculateTotal() {
        // 1. Calculate base consumables cost (applying item-level discounts to each line if any)
        BigDecimal totalConsumablesCost = BigDecimal.ZERO;
        if (consumablesUsed != null) {
            for (TreatmentInvoiceConsumable line : consumablesUsed) {
                // Compute base line cost
                BigDecimal lineCost = line.getActualQuantity().multiply(line.getPricePerUnit());
                
                // Check for item-level discount
                BigDecimal lineDiscount = BigDecimal.ZERO;
                if (discounts != null) {
                    for (InvoiceDiscount disc : discounts) {
                        if (disc.getTarget() == DiscountTarget.ITEM && line.getStockItem().getId().equals(disc.getTargetId())) {
                            if (disc.getType() == DiscountType.PERCENTAGE) {
                                lineDiscount = lineCost.multiply(disc.getValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                            } else {
                                lineDiscount = disc.getValue();
                            }
                            break;
                        }
                    }
                }
                
                lineCost = lineCost.subtract(lineDiscount).max(BigDecimal.ZERO);
                line.setTotalCost(lineCost);
                totalConsumablesCost = totalConsumablesCost.add(lineCost);
            }
        }
        this.consumablesCost = totalConsumablesCost;

        // 2. Calculate treatment fee after treatment-level discounts
        BigDecimal actualTreatmentPrice = this.treatmentPrice;
        BigDecimal treatmentDiscount = BigDecimal.ZERO;
        if (discounts != null && actualTreatmentPrice != null) {
            for (InvoiceDiscount disc : discounts) {
                if (disc.getTarget() == DiscountTarget.TREATMENT) {
                    if (disc.getType() == DiscountType.PERCENTAGE) {
                        treatmentDiscount = actualTreatmentPrice.multiply(disc.getValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                    } else {
                        treatmentDiscount = disc.getValue();
                    }
                    break;
                }
            }
        }
        actualTreatmentPrice = actualTreatmentPrice != null ? actualTreatmentPrice.subtract(treatmentDiscount).max(BigDecimal.ZERO) : BigDecimal.ZERO;

        // 3. Compute Subtotal (before invoice-level discount but after item & treatment discounts)
        this.subtotal = actualTreatmentPrice.add(totalConsumablesCost);

        // 4. Apply invoice-level discount
        BigDecimal invoiceDiscount = BigDecimal.ZERO;
        if (discounts != null) {
            for (InvoiceDiscount disc : discounts) {
                if (disc.getTarget() == DiscountTarget.INVOICE) {
                    if (disc.getType() == DiscountType.PERCENTAGE) {
                        invoiceDiscount = this.subtotal.multiply(disc.getValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                    } else {
                        invoiceDiscount = disc.getValue();
                    }
                    break;
                }
            }
        }

        // Final total
        this.total = this.subtotal.subtract(invoiceDiscount).max(BigDecimal.ZERO);
        
        // Sum total discounts for display
        BigDecimal totalItemDiscounts = BigDecimal.ZERO;
        if (consumablesUsed != null) {
            for (TreatmentInvoiceConsumable line : consumablesUsed) {
                BigDecimal baseLineCost = line.getActualQuantity().multiply(line.getPricePerUnit());
                totalItemDiscounts = totalItemDiscounts.add(baseLineCost.subtract(line.getTotalCost()));
            }
        }
        this.discountAmount = totalItemDiscounts.add(treatmentDiscount).add(invoiceDiscount);
    }

    /**
     * The amount actually billed to the patient when this session is
     * finalized (ADR 0005): the treatment fee net of any TREATMENT-target
     * discount. Deliberately excludes consumablesCost — per the ADR 0005
     * decision, the patient is billed one line for the treatment fee, not
     * itemized by consumable — and excludes ITEM/INVOICE-target discounts,
     * which stay internal cost-tracking concerns for now (a v1
     * simplification, not a claim that they never affect the patient's
     * bill; see the TreatmentInvoiceService#finalizeInvoice call site).
     */
    public BigDecimal patientChargeAmount() {
        BigDecimal price = treatmentPrice != null ? treatmentPrice : BigDecimal.ZERO;
        BigDecimal treatmentDiscount = BigDecimal.ZERO;
        if (discounts != null) {
            for (InvoiceDiscount disc : discounts) {
                if (disc.getTarget() == DiscountTarget.TREATMENT) {
                    treatmentDiscount = disc.getType() == DiscountType.PERCENTAGE
                            ? price.multiply(disc.getValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
                            : disc.getValue();
                    break;
                }
            }
        }
        return price.subtract(treatmentDiscount).max(BigDecimal.ZERO);
    }

    public void addConsumable(TreatmentInvoiceConsumable consumable) {
        consumablesUsed.add(consumable);
        consumable.setTreatmentInvoice(this);
    }

    public void addDiscount(InvoiceDiscount discount) {
        discounts.add(discount);
        discount.setTreatmentInvoice(this);
    }
}
