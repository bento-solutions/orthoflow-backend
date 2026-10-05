package com.orthoflow.billing.infrastructure.adapter.persistence;

import com.orthoflow.billing.domain.model.Cheque;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChequeJpaRepository extends JpaRepository<Cheque, UUID> {

    Optional<Cheque> findByIdAndPracticeId(UUID id, UUID practiceId);

    @Query("""
            SELECT c FROM Cheque c
            WHERE c.practiceId = :practiceId
              AND (:status IS NULL OR c.status = :status)
              AND (:patientId IS NULL OR c.patientId = :patientId)
              AND (:guarantee IS NULL OR c.guarantee = :guarantee)
              AND (:dueTo IS NULL OR c.dueDate <= :dueTo)
            ORDER BY c.dueDate, c.createdAt
            """)
    List<Cheque> search(@Param("practiceId") UUID practiceId, @Param("status") Cheque.Status status,
                        @Param("patientId") UUID patientId, @Param("guarantee") Boolean guarantee,
                        @Param("dueTo") LocalDate dueTo);
}
