package com.orthoflow.finance.infrastructure;

import com.orthoflow.finance.domain.model.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseCategoryJpaRepository extends JpaRepository<ExpenseCategory, UUID> {

    List<ExpenseCategory> findByPracticeIdOrderByDisplayOrderAscNameFrAsc(UUID practiceId);

    Optional<ExpenseCategory> findByIdAndPracticeId(UUID id, UUID practiceId);

    Optional<ExpenseCategory> findByPracticeIdAndCode(UUID practiceId, String code);

    boolean existsByPracticeIdAndCode(UUID practiceId, String code);
}
