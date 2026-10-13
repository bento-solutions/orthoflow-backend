package com.orthoflow.consultation.domain.repository;

import com.orthoflow.consultation.domain.model.ConsultationTranscriptLine;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ConsultationLineRepository {
    void saveAll(Collection<ConsultationTranscriptLine> lines);

    /** In the order they were said. */
    List<ConsultationTranscriptLine> findByConsultation(UUID consultationId);

    /** The sequence numbers already stored, so a resent line is not stored twice. */
    Set<Integer> seqsOf(UUID consultationId);

    void deleteByConsultation(UUID consultationId);
}
