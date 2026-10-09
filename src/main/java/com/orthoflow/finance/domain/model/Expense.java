package com.orthoflow.finance.domain.model;

import org.hibernate.annotations.TenantId;
import com.orthoflow.billing.domain.model.PaymentMethod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Money the clinic spends. Created by hand, by a validated supplier invoice, by
 * a lab order received, or by a recurring rule. A recurring expense is a
 * template: each period a copy is generated and the template's next date moves on.
 */
@Entity
@Table(name = "expenses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Expense {

    public enum Status { PENDING, PAID, CANCELLED }

    public enum Recurrence { NONE, MONTHLY, QUARTERLY, YEARLY }

    @Id
    private UUID id;

    @Version
    private Long version;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    private String payee;

    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    @Column(name = "receipt_file_id")
    private UUID receiptFileId;

    @Column(name = "vendor_invoice_id")
    private UUID vendorInvoiceId;

    @Column(name = "lab_order_id")
    private UUID labOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Recurrence recurrence = Recurrence.NONE;

    @Column(name = "recurrence_next")
    private LocalDate recurrenceNext;

    @Column(name = "recurrence_parent_id")
    private UUID recurrenceParentId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
