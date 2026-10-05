package com.orthoflow.finance.infrastructure;

import com.orthoflow.finance.domain.model.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseJpaRepository extends JpaRepository<Expense, UUID> {

    Optional<Expense> findByIdAndPracticeId(UUID id, UUID practiceId);

    Optional<Expense> findByVendorInvoiceId(UUID vendorInvoiceId);

    Optional<Expense> findByLabOrderId(UUID labOrderId);

    @Query("""
            SELECT e FROM Expense e
            WHERE e.practiceId = :practiceId AND e.expenseDate BETWEEN :from AND :to
              AND (:categoryId IS NULL OR e.categoryId = :categoryId)
              AND (:status IS NULL OR e.status = :status)
              AND (:search IS NULL OR lower(coalesce(e.payee, '')) LIKE :search ESCAPE '\\'
                   OR lower(coalesce(e.description, '')) LIKE :search ESCAPE '\\')
            ORDER BY e.expenseDate DESC, e.createdAt DESC
            """)
    List<Expense> search(@Param("practiceId") UUID practiceId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                         @Param("categoryId") UUID categoryId, @Param("status") Expense.Status status,
                         @Param("search") String search);

    /** Recurrence templates whose next copy is due. */
    @Query("""
            SELECT e FROM Expense e
            WHERE e.recurrence <> com.orthoflow.finance.domain.model.Expense$Recurrence.NONE
              AND e.recurrenceNext <= :today AND e.status <> com.orthoflow.finance.domain.model.Expense$Status.CANCELLED
            """)
    List<Expense> recurringDue(@Param("today") LocalDate today);
}
