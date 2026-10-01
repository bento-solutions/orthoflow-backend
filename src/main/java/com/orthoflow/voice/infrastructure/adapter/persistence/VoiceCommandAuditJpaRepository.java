package com.orthoflow.voice.infrastructure.adapter.persistence;

import com.orthoflow.voice.domain.model.ConfirmationStatus;
import com.orthoflow.voice.domain.model.VoiceCommandAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VoiceCommandAuditJpaRepository extends JpaRepository<VoiceCommandAudit, UUID> {
    List<VoiceCommandAudit> findByPatientIdOrderByOccurredAtDesc(UUID patientId);
    List<VoiceCommandAudit> findBySessionIdOrderByOccurredAtAsc(UUID sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update VoiceCommandAudit a
               set a.transcript = null, a.entities = null, a.previousValue = null,
                   a.newValue = null, a.errorMessage = null, a.targetId = null
             where a.patientId = :patientId
            """)
    int scrubPatientData(@Param("patientId") UUID patientId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update VoiceCommandAudit a set a.confirmationStatus = :to "
            + "where a.id = :id and a.confirmationStatus = :from")
    int transition(@Param("id") UUID id, @Param("from") ConfirmationStatus from, @Param("to") ConfirmationStatus to);
}
