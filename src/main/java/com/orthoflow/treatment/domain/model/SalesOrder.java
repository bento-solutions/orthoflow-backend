package com.orthoflow.treatment.domain.model;

import org.hibernate.annotations.TenantId;
import com.orthoflow.patient.application.port.PatientSummary;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "sales_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesOrder {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "so_number", unique = true, nullable = false)
    private String soNumber;

    // A plain UUID rather than a @ManyToOne Patient (audit I.2 / docs/adr/0001-patient-in-billing.md).
    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /**
     * Not persisted — populated by the service layer via PatientLookup
     * before this entity is serialized as a response, so the JSON shape the
     * frontend already depends on ({@code so.patient.firstName} etc. in the
     * sales-orders list) stays the same even though the JPA relation is
     * gone. Controllers here still return entities directly (audit I.5 is
     * still open); this is a deliberately narrow accommodation for that,
     * not a precedent for adding more transient view state to the entity.
     */
    @Transient
    @Builder.Default
    private PatientSummary patient = null;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SOStatus status = SOStatus.DRAFT;

    @OneToMany(mappedBy = "salesOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SalesOrderLine> lines = new ArrayList<>();

    @Column(name = "total_amount", nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        calculateTotal();
    }

    @PreUpdate
    public void preUpdate() {
        calculateTotal();
    }

    public void calculateTotal() {
        if (lines != null) {
            this.totalAmount = lines.stream()
                    .map(SalesOrderLine::calculateLineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public void addLine(SalesOrderLine line) {
        lines.add(line);
        line.setSalesOrder(this);
    }
}
