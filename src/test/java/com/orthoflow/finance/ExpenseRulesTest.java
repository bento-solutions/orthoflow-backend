package com.orthoflow.finance;

import com.orthoflow.finance.application.service.RecurringExpenseJob;
import com.orthoflow.finance.application.service.VendorInvoiceExpenseBridge;
import com.orthoflow.finance.domain.model.Expense;
import com.orthoflow.finance.domain.model.ExpenseCategory;
import com.orthoflow.finance.infrastructure.ExpenseCategoryJpaRepository;
import com.orthoflow.finance.infrastructure.ExpenseJpaRepository;
import com.orthoflow.procurement.application.port.VendorInvoiceListener.Summary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExpenseRulesTest {

    private final UUID practice = UUID.randomUUID();
    private ExpenseJpaRepository expenses;
    private ExpenseCategoryJpaRepository categories;

    @BeforeEach
    void setUp() {
        expenses = mock(ExpenseJpaRepository.class);
        categories = mock(ExpenseCategoryJpaRepository.class);
    }

    @Test
    void rentGeneratesOneCopyForEveryMonthItMissedAndMovesOn() {
        Expense rent = Expense.builder().id(UUID.randomUUID()).practiceId(practice).categoryId(UUID.randomUUID()).payee("Propriétaire")
                .amount(new BigDecimal("4000")).expenseDate(LocalDate.of(2026, 6, 1)).recurrence(Expense.Recurrence.MONTHLY)
                .recurrenceNext(LocalDate.of(2026, 7, 1)).build();
        when(expenses.recurringDue(LocalDate.of(2026, 10, 5))).thenReturn(List.of(rent));
        when(expenses.findById(rent.getId())).thenReturn(Optional.of(rent));
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

        int created = new RecurringExpenseJob(expenses, tx).generate(LocalDate.of(2026, 10, 5));

        assertThat(created).isEqualTo(4);
        ArgumentCaptor<Expense> copies = ArgumentCaptor.forClass(Expense.class);
        verify(expenses, times(4)).save(copies.capture());
        assertThat(copies.getAllValues()).extracting(Expense::getExpenseDate)
                .containsExactly(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
        assertThat(copies.getAllValues()).allSatisfy(c -> {
            assertThat(c.getRecurrence()).isEqualTo(Expense.Recurrence.NONE);
            assertThat(c.getRecurrenceParentId()).isEqualTo(rent.getId());
            assertThat(c.getStatus()).isEqualTo(Expense.Status.PENDING);
            assertThat(c.getAmount()).isEqualByComparingTo("4000");
        });
        assertThat(rent.getRecurrenceNext()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void aTemplateNotYetDueGeneratesNothing() {
        when(expenses.recurringDue(any())).thenReturn(List.of());

        assertThat(new RecurringExpenseJob(expenses, mock(TransactionTemplate.class)).generate(LocalDate.of(2026, 10, 5))).isZero();
    }

    @Test
    void validatingASupplierInvoiceBooksOneExpenseInSuppliesDueAfterItsTerms() {
        ExpenseCategory supplies = ExpenseCategory.builder().id(UUID.randomUUID()).practiceId(practice).code("SUPPLIES").build();
        when(categories.findByPracticeIdAndCode(practice, "SUPPLIES")).thenReturn(Optional.of(supplies));
        when(expenses.findByVendorInvoiceId(any())).thenReturn(Optional.empty());
        UUID vendorInvoice = UUID.randomUUID();

        new VendorInvoiceExpenseBridge(expenses, categories).onValidated(new Summary(vendorInvoice, practice, "VI-2026-0001", "Dentsply",
                LocalDate.of(2026, 10, 1), new BigDecimal("1800.00"), "Net 30 jours", UUID.randomUUID()));

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(expenses).save(saved.capture());
        Expense e = saved.getValue();
        assertThat(e.getCategoryId()).isEqualTo(supplies.getId());
        assertThat(e.getVendorInvoiceId()).isEqualTo(vendorInvoice);
        assertThat(e.getPayee()).isEqualTo("Dentsply");
        assertThat(e.getAmount()).isEqualByComparingTo("1800.00");
        assertThat(e.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(e.getStatus()).isEqualTo(Expense.Status.PENDING);
    }

    @Test
    void validatingTwiceNeverDoubleCounts() {
        when(expenses.findByVendorInvoiceId(any())).thenReturn(Optional.of(Expense.builder().build()));

        new VendorInvoiceExpenseBridge(expenses, categories).onValidated(new Summary(UUID.randomUUID(), practice, "VI", "X",
                LocalDate.now(), BigDecimal.TEN, null, null));

        verify(expenses, never()).save(any());
    }

    @Test
    void cancellingTheInvoiceCancelsAnUnpaidExpenseButLeavesAPaidOneAlone() {
        Expense pending = Expense.builder().status(Expense.Status.PENDING).build();
        Expense paid = Expense.builder().status(Expense.Status.PAID).build();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(expenses.findByVendorInvoiceId(a)).thenReturn(Optional.of(pending));
        when(expenses.findByVendorInvoiceId(b)).thenReturn(Optional.of(paid));
        VendorInvoiceExpenseBridge bridge = new VendorInvoiceExpenseBridge(expenses, categories);

        bridge.onCancelled(a);
        bridge.onCancelled(b);

        assertThat(pending.getStatus()).isEqualTo(Expense.Status.CANCELLED);
        assertThat(paid.getStatus()).isEqualTo(Expense.Status.PAID);
    }

    @Test
    void paymentTermsAreReadAsDaysOrIgnored() throws Exception {
        Method due = VendorInvoiceExpenseBridge.class.getDeclaredMethod("dueDate", LocalDate.class, String.class);
        due.setAccessible(true);
        LocalDate day = LocalDate.of(2026, 1, 1);

        assertThat(due.invoke(null, day, "30 days")).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(due.invoke(null, day, "Net 45")).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(due.invoke(null, day, "à réception")).isNull();
        assertThat(due.invoke(null, day, null)).isNull();
    }
}
