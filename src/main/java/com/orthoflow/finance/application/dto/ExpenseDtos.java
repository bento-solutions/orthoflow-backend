package com.orthoflow.finance.application.dto;

import com.orthoflow.billing.domain.model.PaymentMethod;
import com.orthoflow.finance.domain.model.Expense;
import com.orthoflow.finance.domain.model.ExpenseCategory;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class ExpenseDtos {

    private ExpenseDtos() {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "ExpenseRequest")

    public record Request(@NotNull LocalDate expenseDate, @NotNull UUID categoryId, @Size(max = 200) String payee,
                          @Size(max = 500) String description, @NotNull @DecimalMin("0.01") BigDecimal amount,
                          LocalDate dueDate, Expense.Recurrence recurrence, String notes) {
    }

    public record MarkPaid(LocalDate paidDate, @NotNull PaymentMethod method) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "ExpenseView")

    public record View(UUID id, LocalDate expenseDate, UUID categoryId, String categoryName, ExpenseCategory.Kind categoryKind,
                       String payee, String description, BigDecimal amount, LocalDate dueDate, LocalDate paidDate,
                       Expense.Status status, PaymentMethod method, UUID receiptFileId, UUID vendorInvoiceId, UUID labOrderId,
                       Expense.Recurrence recurrence, LocalDate recurrenceNext, String notes, boolean overdue) {
    }

    public record CategoryRequest(@Size(max = 40) @Pattern(regexp = "^[A-Z0-9_]*$") String code, @NotBlank @Size(max = 120) String nameFr,
                                  @NotBlank @Size(max = 120) String nameEn, @NotBlank @Size(max = 120) String nameAr,
                                  ExpenseCategory.Kind kind, Boolean active, Integer displayOrder) {
    }

    public record CategoryView(UUID id, String code, String nameFr, String nameEn, String nameAr, ExpenseCategory.Kind kind,
                               boolean active, int displayOrder) {
        public static CategoryView from(ExpenseCategory c) {
            return new CategoryView(c.getId(), c.getCode(), c.getNameFr(), c.getNameEn(), c.getNameAr(), c.getKind(),
                    c.isActive(), c.getDisplayOrder());
        }
    }
}
