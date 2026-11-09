package com.orthoflow.inventory.infrastructure.adapter.persistence;

import com.orthoflow.inventory.domain.model.StockItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.orthoflow.procurement.domain.model.DeliveryNoteLine;

public interface StockItemJpaRepository
        extends JpaRepository<StockItem, UUID>, JpaSpecificationExecutor<StockItem> {
    Optional<StockItem> findBySku(String sku);

    /**
     * Locks the row for the duration of the transaction so two concurrent
     * deductions can't both read the same currentStock, both pass the
     * insufficient-stock check, and both write — the classic check-then-act
     * race that let stock go negative under load (audit II.6). The DB-level
     * CHECK (current_stock >= 0) from V11 is the backstop; this lock is what
     * keeps the check in recordMovement() actually meaningful.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockItem s WHERE s.id = :id")
    Optional<StockItem> findByIdForUpdate(UUID id);

    @Query("SELECT s FROM StockItem s WHERE s.active = true AND s.currentStock <= s.minimumStock")
    List<StockItem> findLowStock();

    @Query("SELECT DISTINCT dnl.stockItem FROM DeliveryNoteLine dnl " +
            "WHERE dnl.stockItem.active = true AND dnl.expiryDate BETWEEN :today AND :cutoff")
    List<StockItem> findExpiring(@Param("today") LocalDate today, @Param("cutoff") LocalDate cutoff);

    @org.springframework.data.jpa.repository.Query("""
            SELECT COALESCE(SUM(COALESCE(i.currentStock, 0) * COALESCE(i.purchasePrice, 0)), 0),
                   SUM(CASE WHEN COALESCE(i.currentStock, 0) <= 0 THEN 1 ELSE 0 END)
            FROM StockItem i WHERE i.active = true
            """)
    java.util.List<Object[]> activeTotals();
}
