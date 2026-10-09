package com.orthoflow.billing.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A fee note (note d'honoraires) or a mutual-insurance care form that was issued
 * to a patient. The PDF is kept exactly as issued; the row tracks whether it was
 * handed over, and a reprint is a separate row marked as a duplicate of the
 * original rather than a second "original".
 */
@Entity
@Table(name = "tax_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxDocument {

    public enum Kind { FEE_NOTE, CARE_FORM }

    public enum Status { ISSUED, DELIVERED, VOID }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String number;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Column(name = "insurer_id")
    private UUID insurerId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "duplicate_of")
    private UUID duplicateOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ISSUED;

    @Column(name = "file_id")
    private UUID fileId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (issuedAt == null) issuedAt = OffsetDateTime.now();
    }
}
