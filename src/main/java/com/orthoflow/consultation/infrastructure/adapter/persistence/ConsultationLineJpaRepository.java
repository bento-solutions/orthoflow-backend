package com.orthoflow.consultation.infrastructure.adapter.persistence;

import com.orthoflow.consultation.domain.model.ConsultationTranscriptLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ConsultationLineJpaRepository extends JpaRepository<ConsultationTranscriptLine, UUID> {
    List<ConsultationTranscriptLine> findByConsultationIdOrderBySeqAsc(UUID consultationId);

    @Query("select l.seq from ConsultationTranscriptLine l where l.consultationId = :consultationId")
    List<Integer> findSeqs(@Param("consultationId") UUID consultationId);

    @Modifying
    @Query("delete from ConsultationTranscriptLine l where l.consultationId = :consultationId")
    int deleteByConsultationId(@Param("consultationId") UUID consultationId);
}
