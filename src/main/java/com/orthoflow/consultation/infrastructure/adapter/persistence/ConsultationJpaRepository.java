package com.orthoflow.consultation.infrastructure.adapter.persistence;

import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ConsultationJpaRepository extends JpaRepository<Consultation, UUID> {
    List<Consultation> findByPatientIdOrderByStartedAtDesc(UUID patientId);

    List<Consultation> findByPatientIdAndStatusInOrderByStartedAtDesc(UUID patientId,
                                                                      Collection<ConsultationStatus> statuses);

    @Modifying
    @Transactional
    @Query("update Consultation c set c.reviewState = :state, c.lastActivityAt = :now "
            + "where c.id = :id and c.status in :open")
    int saveReviewState(@Param("id") UUID id, @Param("state") String state, @Param("now") OffsetDateTime now,
                        @Param("open") Collection<ConsultationStatus> open);

    @Query("select c from Consultation c where c.status in :open "
            + "and coalesce(c.lastActivityAt, c.startedAt) < :before")
    List<Consultation> findIdle(@Param("open") Collection<ConsultationStatus> open,
                                @Param("before") OffsetDateTime before);
}
