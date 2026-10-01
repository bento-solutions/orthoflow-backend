package com.orthoflow.voice.infrastructure.adapter.persistence;

import com.orthoflow.voice.domain.model.ConfirmationStatus;
import com.orthoflow.voice.domain.model.VoiceCommandAudit;
import com.orthoflow.voice.domain.repository.VoiceCommandAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class VoiceCommandAuditRepositoryAdapter implements VoiceCommandAuditRepository {

    private final VoiceCommandAuditJpaRepository jpaRepository;

    @Override
    public VoiceCommandAudit save(VoiceCommandAudit entry) {
        return jpaRepository.save(entry);
    }

    @Override
    public Optional<VoiceCommandAudit> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<VoiceCommandAudit> findByPatient(UUID patientId) {
        return jpaRepository.findByPatientIdOrderByOccurredAtDesc(patientId);
    }

    @Override
    public List<VoiceCommandAudit> findBySession(UUID sessionId) {
        return jpaRepository.findBySessionIdOrderByOccurredAtAsc(sessionId);
    }

    @Override
    public int scrubPatientData(UUID patientId) {
        return jpaRepository.scrubPatientData(patientId);
    }

    @Override
    public boolean transitionConfirmation(UUID id, ConfirmationStatus from, ConfirmationStatus to) {
        return jpaRepository.transition(id, from, to) == 1;
    }
}
