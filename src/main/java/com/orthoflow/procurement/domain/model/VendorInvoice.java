package com.orthoflow.procurement.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.orthoflow.inventory.domain.model.Supplier;

@Entity
@Table(name = "vendor_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorInvoice {

    @Id
    private UUID id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = com.orthoflow.common.tenancy.Practices.DEFAULT_ID;

    @Column(name = "vendor_invoice_number", unique = true, nullable = false)
    private String vendorInvoiceNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "delivery_note_id", nullable = false)
    private DeliveryNote deliveryNote;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VendorInvoiceStatus status = VendorInvoiceStatus.DRAFT;

    @OneToMany(mappedBy = "vendorInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<VendorInvoiceLine> lines = new ArrayList<>();

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "invoice_amount", nullable = false)
    private BigDecimal invoiceAmount;

    @Column(name = "payment_terms")
    private String paymentTerms;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "validated_by")
    private UUID validatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "validated_at")
    private OffsetDateTime validatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (invoiceDate == null) {
            invoiceDate = LocalDate.now();
        }
    }

    public void addLine(VendorInvoiceLine line) {
        lines.add(line);
        line.setVendorInvoice(this);
    }
}
