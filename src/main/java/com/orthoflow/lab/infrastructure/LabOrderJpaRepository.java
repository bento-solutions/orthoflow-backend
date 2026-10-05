package com.orthoflow.lab.infrastructure;

import com.orthoflow.lab.domain.model.LabOrder;
import com.orthoflow.lab.domain.model.LabStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabOrderJpaRepository extends JpaRepository<LabOrder, UUID> {

    Optional<LabOrder> findByIdAndPracticeId(UUID id, UUID practiceId);

    @Query("""
            SELECT o FROM LabOrder o
            WHERE o.practiceId = :practiceId
              AND (:statusesEmpty = true OR o.status IN :statuses)
              AND (:dueFrom IS NULL OR o.dueDate >= :dueFrom) AND (:dueTo IS NULL OR o.dueDate <= :dueTo)
              AND o.updatedAt >= :updatedFrom AND o.updatedAt < :updatedTo
              AND (:urgentOnly = false OR o.urgent = true)
              AND (:patientId IS NULL OR o.patientId = :patientId) AND (:labId IS NULL OR o.labId = :labId)
            ORDER BY o.urgent DESC, o.dueDate ASC NULLS LAST, o.createdAt DESC
            """)
    List<LabOrder> search(@Param("practiceId") UUID practiceId, @Param("statusesEmpty") boolean statusesEmpty,
                          @Param("statuses") Collection<LabStatus> statuses, @Param("dueFrom") LocalDate dueFrom,
                          @Param("dueTo") LocalDate dueTo, @Param("updatedFrom") OffsetDateTime updatedFrom,
                          @Param("updatedTo") OffsetDateTime updatedTo, @Param("urgentOnly") boolean urgentOnly,
                          @Param("patientId") UUID patientId, @Param("labId") UUID labId);
}
