package com.orthoflow.inventory.infrastructure.adapter.persistence;

import com.orthoflow.inventory.domain.model.MovementType;
import com.orthoflow.inventory.domain.model.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface StockMovementJpaRepository
        extends JpaRepository<StockMovement, UUID>, JpaSpecificationExecutor<StockMovement> {
    List<StockMovement> findByStockItemId(UUID stockItemId);
    List<StockMovement> findByMovementTypeAndCreatedAtBetween(MovementType movementType, OffsetDateTime start, OffsetDateTime end);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COALESCE(SUM(COALESCE(m.quantity, 0) * COALESCE(i.purchasePrice, 0)), 0)
            FROM StockMovement m JOIN m.stockItem i
            WHERE m.movementType = :type AND m.createdAt BETWEEN :start AND :end
            """)
    java.math.BigDecimal costOfMovementsBetween(@org.springframework.data.repository.query.Param("type") MovementType type,
                                                @org.springframework.data.repository.query.Param("start") java.time.OffsetDateTime start,
                                                @org.springframework.data.repository.query.Param("end") java.time.OffsetDateTime end);

    @org.springframework.data.jpa.repository.Query("""
            SELECT i.id, i.name, i.purchasePrice, COALESCE(SUM(m.quantity), 0)
            FROM StockMovement m JOIN m.stockItem i
            WHERE m.movementType = :type AND m.createdAt BETWEEN :start AND :end
            GROUP BY i.id, i.name, i.purchasePrice
            ORDER BY COALESCE(SUM(m.quantity), 0) DESC
            """)
    java.util.List<Object[]> topItemsBetween(@org.springframework.data.repository.query.Param("type") MovementType type,
                                             @org.springframework.data.repository.query.Param("start") java.time.OffsetDateTime start,
                                             @org.springframework.data.repository.query.Param("end") java.time.OffsetDateTime end,
                                             org.springframework.data.domain.Pageable page);
}
