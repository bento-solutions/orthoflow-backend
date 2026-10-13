package com.orthoflow.inventory.infrastructure.adapter.persistence;

import com.orthoflow.inventory.domain.model.StockItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface StockForecastJpaRepository extends JpaRepository<StockItem, UUID> {

    interface MonthlyUsage {
        UUID getStockItemId();
        LocalDate getMonth();
        BigDecimal getQuantity();
    }

    /**
     * How much of each item left the shelf, by calendar month.
     *
     * <p>Counts OUT and WRITE_OFF: a box that was thrown away still has to be replaced,
     * so leaving write-offs out would under-order exactly the items that go bad.
     * Returns and deliveries are not consumption and are excluded.
     *
     * <p>The current, part-finished month is excluded by the caller — folding a month
     * that is three days old into an average of full months drags every forecast down.
     */
    @Query(value = """
        SELECT m.stock_item_id                              AS stockItemId,
               CAST(date_trunc('month', m.created_at) AS date) AS month,
               SUM(m.quantity)                              AS quantity
        FROM stock_movements m
        WHERE m.practice_id = :practiceId
          AND m.movement_type IN ('OUT', 'WRITE_OFF')
          AND m.created_at >= :since
        GROUP BY 1, 2
        ORDER BY 1, 2
        """, nativeQuery = true)
    List<MonthlyUsage> monthlyUsageSince(@Param("practiceId") UUID practiceId,
                                         @Param("since") java.time.OffsetDateTime since);
}
