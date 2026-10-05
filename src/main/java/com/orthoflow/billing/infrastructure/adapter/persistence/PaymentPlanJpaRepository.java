package com.orthoflow.billing.infrastructure.adapter.persistence;

import com.orthoflow.billing.domain.model.PaymentPlan;
import com.orthoflow.billing.domain.model.PaymentPlanInstalment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPlanJpaRepository extends JpaRepository<PaymentPlan, UUID> {

    Optional<PaymentPlan> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<PaymentPlan> findByPatientIdAndPracticeIdOrderByCreatedAtDesc(UUID patientId, UUID practiceId);

    /** Instalments waiting to be paid and due by a date, soonest first: the "to do today" list. */
    @Query("""
            SELECT i FROM PaymentPlanInstalment i JOIN FETCH i.plan p
            WHERE p.practiceId = :practiceId AND p.status = com.orthoflow.billing.domain.model.PaymentPlan$Status.ACTIVE
              AND i.status = com.orthoflow.billing.domain.model.PaymentPlanInstalment$Status.PENDING AND i.dueDate <= :until
            ORDER BY i.dueDate, p.createdAt
            """)
    List<PaymentPlanInstalment> due(@Param("practiceId") UUID practiceId, @Param("until") LocalDate until);

    @Query("SELECT i FROM PaymentPlanInstalment i JOIN FETCH i.plan p WHERE i.id = :id AND p.practiceId = :practiceId")
    Optional<PaymentPlanInstalment> findInstalment(@Param("id") UUID id, @Param("practiceId") UUID practiceId);
}
