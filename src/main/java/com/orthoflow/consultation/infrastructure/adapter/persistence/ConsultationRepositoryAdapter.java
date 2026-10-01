package com.orthoflow.consultation.infrastructure.adapter.persistence;

import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.domain.repository.ConsultationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ConsultationRepositoryAdapter implements ConsultationRepository {

    private static final List<ConsultationStatus> OPEN = List.of(
            ConsultationStatus.INTAKE, ConsultationStatus.EXAMINATION, ConsultationStatus.REVIEW);

    private final ConsultationJpaRepository jpaRepository;

    @Override
    public Consultation save(Consultation consultation) {
        return jpaRepository.save(consultation);
    }

    @Override
    public Optional<Consultation> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Consultation> findByPatient(UUID patientId) {
        return jpaRepository.findByPatientIdOrderByStartedAtDesc(patientId);
    }

    @Override
    public Optional<Consultation> findOpenByPatient(UUID patientId) {
        return jpaRepository.findByPatientIdAndStatusInOrderByStartedAtDesc(patientId, OPEN).stream().findFirst();
    }

    @Override
    public boolean saveReviewState(UUID id, String reviewState) {
        return jpaRepository.saveReviewState(id, reviewState, OffsetDateTime.now(), OPEN) > 0;
    }

    @Override
    public List<Consultation> findIdleOpen(OffsetDateTime before) {
        return jpaRepository.findIdle(OPEN, before);
    }
}
