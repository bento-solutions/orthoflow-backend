package com.orthoflow.finance.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "expense_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpenseCategory {

    /** How the monthly equation treats a category: salaries and social charges are shown apart from operating costs. */
    public enum Kind { OPERATING, SALARY, SOCIAL, TAX, LAB, SUPPLIES }

    @Id
    private UUID id;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(nullable = false)
    private String code;

    @Column(name = "name_fr", nullable = false)
    private String nameFr;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_ar", nullable = false)
    private String nameAr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Kind kind = Kind.OPERATING;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }
}
