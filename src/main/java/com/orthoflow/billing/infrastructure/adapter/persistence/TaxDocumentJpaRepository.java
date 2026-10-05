package com.orthoflow.billing.infrastructure.adapter.persistence;

import com.orthoflow.billing.domain.model.TaxDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaxDocumentJpaRepository extends JpaRepository<TaxDocument, UUID> {

    Optional<TaxDocument> findByIdAndPracticeId(UUID id, UUID practiceId);

    boolean existsByInvoiceIdAndKindAndDuplicateOfIsNullAndStatusNot(UUID invoiceId, TaxDocument.Kind kind, TaxDocument.Status status);

    long countByDuplicateOf(UUID original);

    @Query("""
            SELECT d FROM TaxDocument d
            WHERE d.practiceId = :practiceId AND d.issuedAt >= :from AND d.issuedAt < :to
              AND (:kind IS NULL OR d.kind = :kind) AND (:patientId IS NULL OR d.patientId = :patientId)
              AND (:status IS NULL OR d.status = :status)
              AND (:dupMode = 'ANY' OR (:dupMode = 'ONLY' AND d.duplicateOf IS NOT NULL) OR (:dupMode = 'NONE' AND d.duplicateOf IS NULL))
            ORDER BY d.issuedAt DESC
            """)
    List<TaxDocument> search(@Param("practiceId") UUID practiceId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                             @Param("kind") TaxDocument.Kind kind, @Param("patientId") UUID patientId,
                             @Param("status") TaxDocument.Status status, @Param("dupMode") String dupMode);
}
