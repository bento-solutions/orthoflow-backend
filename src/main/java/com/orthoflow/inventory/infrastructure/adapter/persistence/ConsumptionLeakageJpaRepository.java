package com.orthoflow.inventory.infrastructure.adapter.persistence;

import com.orthoflow.inventory.domain.model.StockConsumptionVariance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ConsumptionLeakageJpaRepository extends JpaRepository<StockConsumptionVariance, UUID> {

    /** One row per doctor and consumable: what the recipe expected, what actually left the shelf, and why. */
    interface LeakageRow {
        UUID getPractitionerId();
        String getPractitionerName();
        UUID getStockItemId();
        String getItemName();
        String getUnitOfMeasure();
        int getTreatments();
        BigDecimal getExpectedQty();
        BigDecimal getToleratedQty();
        BigDecimal getActualQty();
        BigDecimal getExplainedQty();
        BigDecimal getRetreatmentQty();
        BigDecimal getUnitCost();
    }

    /**
     * Expected consumption against what really left the shelf, per doctor and item.
     *
     * <p>Four separate aggregates folded together rather than one join: expected comes
     * from the bill of materials (as many rows as recipe lines), actual from the stock
     * movements (as many as were recorded), and explained from the variance notes.
     * Joining them before aggregating would multiply the rows against each other and
     * quietly inflate every figure.
     *
     * <p>Re-treatments are excluded from both sides. Their consumption is real, so
     * counting it as a gap would make whoever takes the difficult cases look worst;
     * {@code retreatmentQty} reports it separately instead.
     *
     * <p>Optional recipe lines are left out of the expected side — a line the clinician
     * may or may not need is not a promise to use it.
     */
    @Query(value = """
        WITH done AS (
            SELECT pt.id, pt.practitioner_id, pt.treatment_id
            FROM patient_treatments pt
            WHERE pt.practice_id = :practiceId
              AND pt.status = 'COMPLETED'
              AND pt.retreatment_of_id IS NULL
              AND pt.end_date BETWEEN :fromDate AND :toDate
        ),
        expected AS (
            SELECT d.practitioner_id, tc.stock_item_id,
                   COUNT(DISTINCT d.id)                                                  AS treatments,
                   SUM(tc.quantity_used)                                                 AS expected_qty,
                   SUM(tc.quantity_used * (1 + tc.variance_allowance_pct / 100.0))       AS tolerated_qty
            FROM done d
            JOIN treatment_consumables tc
              ON tc.treatment_id = d.treatment_id AND tc.is_optional = FALSE
            GROUP BY 1, 2
        ),
        actual AS (
            SELECT d.practitioner_id, m.stock_item_id, SUM(m.quantity) AS actual_qty
            FROM done d
            JOIN stock_movements m
              ON m.source_type = 'PATIENT_TREATMENT' AND m.source_id = d.id AND m.movement_type = 'OUT'
            GROUP BY 1, 2
        ),
        explained AS (
            SELECT d.practitioner_id, v.stock_item_id,
                   COALESCE(SUM(v.quantity) FILTER (WHERE v.reason <> 'RE_TREATMENT'), 0) AS explained_qty,
                   COALESCE(SUM(v.quantity) FILTER (WHERE v.reason =  'RE_TREATMENT'), 0) AS retreatment_qty
            FROM done d
            JOIN stock_consumption_variances v ON v.patient_treatment_id = d.id
            GROUP BY 1, 2
        ),
        keys AS (
            SELECT practitioner_id, stock_item_id FROM expected
            UNION
            SELECT practitioner_id, stock_item_id FROM actual
        )
        SELECT k.practitioner_id                        AS practitionerId,
               COALESCE(pr.display_name, 'Unattributed') AS practitionerName,
               k.stock_item_id                          AS stockItemId,
               si.name                                  AS itemName,
               si.unit_of_measure                       AS unitOfMeasure,
               COALESCE(e.treatments, 0)                AS treatments,
               COALESCE(e.expected_qty, 0)              AS expectedQty,
               COALESCE(e.tolerated_qty, 0)             AS toleratedQty,
               COALESCE(a.actual_qty, 0)                AS actualQty,
               COALESCE(x.explained_qty, 0)             AS explainedQty,
               COALESCE(x.retreatment_qty, 0)           AS retreatmentQty,
               si.price_per_use                         AS unitCost
        FROM keys k
        LEFT JOIN expected e  ON e.practitioner_id IS NOT DISTINCT FROM k.practitioner_id AND e.stock_item_id = k.stock_item_id
        LEFT JOIN actual a    ON a.practitioner_id IS NOT DISTINCT FROM k.practitioner_id AND a.stock_item_id = k.stock_item_id
        LEFT JOIN explained x ON x.practitioner_id IS NOT DISTINCT FROM k.practitioner_id AND x.stock_item_id = k.stock_item_id
        JOIN stock_items si   ON si.id = k.stock_item_id
        LEFT JOIN practitioners pr ON pr.id = k.practitioner_id
        ORDER BY 2, 4
        """, nativeQuery = true)
    List<LeakageRow> leakageByPractitioner(@Param("practiceId") UUID practiceId,
                                           @Param("fromDate") LocalDate fromDate,
                                           @Param("toDate") LocalDate toDate);

    /**
     * Stock that left the shelf in the period without a treatment behind it — manual
     * adjustments, write-offs, direct sales. Not leakage in itself, but it is the part
     * of consumption no recipe can account for, and a report that hid it would be
     * claiming more completeness than it has.
     */
    @Query(value = """
        SELECT si.name AS itemName, si.unit_of_measure AS unitOfMeasure,
               m.source_type AS sourceType, SUM(m.quantity) AS quantity,
               SUM(m.quantity * si.price_per_use) AS cost
        FROM stock_movements m
        JOIN stock_items si ON si.id = m.stock_item_id
        WHERE m.practice_id = :practiceId
          AND m.movement_type IN ('OUT', 'WRITE_OFF')
          AND m.source_type <> 'PATIENT_TREATMENT'
          AND CAST(m.created_at AS date) BETWEEN :fromDate AND :toDate
        GROUP BY 1, 2, 3
        ORDER BY 5 DESC NULLS LAST
        """, nativeQuery = true)
    List<UnattributedRow> unattributedConsumption(@Param("practiceId") UUID practiceId,
                                                  @Param("fromDate") LocalDate fromDate,
                                                  @Param("toDate") LocalDate toDate);

    interface RetreatmentRow {
        UUID getPractitionerId();
        BigDecimal getRetreatments();
        BigDecimal getCost();
    }

    /**
     * Work that had to be redone, per doctor.
     *
     * <p>Its consumption is deliberately excluded from {@link #leakageByPractitioner},
     * because the materials really were used and counting them would rank whoever takes
     * the difficult cases worst. It still has to be reported, though — a high
     * re-treatment rate is a quality signal worth seeing, just not a theft one.
     */
    @Query(value = """
        SELECT pt.practitioner_id                        AS practitionerId,
               COUNT(DISTINCT pt.id)                     AS retreatments,
               COALESCE(SUM(m.quantity * si.price_per_use), 0) AS cost
        FROM patient_treatments pt
        LEFT JOIN stock_movements m
               ON m.source_type = 'PATIENT_TREATMENT' AND m.source_id = pt.id AND m.movement_type = 'OUT'
        LEFT JOIN stock_items si ON si.id = m.stock_item_id
        WHERE pt.practice_id = :practiceId
          AND pt.status = 'COMPLETED'
          AND pt.retreatment_of_id IS NOT NULL
          AND pt.end_date BETWEEN :fromDate AND :toDate
        GROUP BY 1
        """, nativeQuery = true)
    List<RetreatmentRow> retreatmentByPractitioner(@Param("practiceId") UUID practiceId,
                                                   @Param("fromDate") LocalDate fromDate,
                                                   @Param("toDate") LocalDate toDate);

    interface UnattributedRow {
        String getItemName();
        String getUnitOfMeasure();
        String getSourceType();
        BigDecimal getQuantity();
        BigDecimal getCost();
    }
}
