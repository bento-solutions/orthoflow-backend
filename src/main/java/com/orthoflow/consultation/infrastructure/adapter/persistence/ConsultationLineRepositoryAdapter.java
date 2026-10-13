package com.orthoflow.consultation.infrastructure.adapter.persistence;

import com.orthoflow.consultation.domain.model.ConsultationTranscriptLine;
import com.orthoflow.consultation.domain.repository.ConsultationLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ConsultationLineRepositoryAdapter implements ConsultationLineRepository {

    private final ConsultationLineJpaRepository jpaRepository;

    @Override
    public void saveAll(Collection<ConsultationTranscriptLine> lines) {
        jpaRepository.saveAll(lines);
    }

    @Override
    public List<ConsultationTranscriptLine> findByConsultation(UUID consultationId) {
        return jpaRepository.findByConsultationIdOrderBySeqAsc(consultationId);
    }

    @Override
    public Set<Integer> seqsOf(UUID consultationId) {
        return new HashSet<>(jpaRepository.findSeqs(consultationId));
    }

    @Override
    @Transactional
    public void deleteByConsultation(UUID consultationId) {
        jpaRepository.deleteByConsultationId(consultationId);
    }
}
