package com.orthoflow.finance.application.service;

import com.orthoflow.finance.domain.model.Expense;
import com.orthoflow.finance.infrastructure.ExpenseCategoryJpaRepository;
import com.orthoflow.finance.infrastructure.ExpenseJpaRepository;
import com.orthoflow.lab.application.port.LabExpenseRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A piece arriving from the lab is a cost: one expense per order, in the lab-fees category. */
@Component
@RequiredArgsConstructor
public class LabExpenseBridge implements LabExpenseRecorder {

    private final ExpenseJpaRepository expenses;
    private final ExpenseCategoryJpaRepository categories;

    @Override
    @Transactional
    public void recordLabFee(UUID practiceId, UUID labOrderId, String labName, BigDecimal amount, LocalDate receivedOn, UUID createdBy) {
        if (amount == null || amount.signum() <= 0 || expenses.findByLabOrderId(labOrderId).isPresent()) {
            return;
        }
        var category = categories.findByPracticeIdAndCode(practiceId, "LAB_FEES")
                .or(() -> categories.findByPracticeIdAndCode(practiceId, "OTHER"));
        category.ifPresent(c -> expenses.save(Expense.builder().practiceId(practiceId).expenseDate(receivedOn).categoryId(c.getId())
                .payee(labName).description("Travaux de laboratoire").amount(amount).labOrderId(labOrderId).createdBy(createdBy).build()));
    }
}
