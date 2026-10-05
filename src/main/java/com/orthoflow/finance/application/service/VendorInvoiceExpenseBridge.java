package com.orthoflow.finance.application.service;

import com.orthoflow.finance.domain.model.Expense;
import com.orthoflow.finance.infrastructure.ExpenseCategoryJpaRepository;
import com.orthoflow.finance.infrastructure.ExpenseJpaRepository;
import com.orthoflow.procurement.application.port.VendorInvoiceListener;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validating a supplier invoice books the expense: what was bought for stock is
 * also money out the door. Linked one-to-one (the column is unique), so a second
 * validation can never double-count, and cancelling the invoice cancels the
 * expense while it is still unpaid.
 */
@Component
@RequiredArgsConstructor
public class VendorInvoiceExpenseBridge implements VendorInvoiceListener {

    private static final Logger log = LoggerFactory.getLogger(VendorInvoiceExpenseBridge.class);
    private static final Pattern DAYS = Pattern.compile("(\\d{1,3})");

    private final ExpenseJpaRepository expenses;
    private final ExpenseCategoryJpaRepository categories;

    @Override
    @Transactional
    public void onValidated(Summary invoice) {
        if (expenses.findByVendorInvoiceId(invoice.id()).isPresent()) {
            return;
        }
        var category = categories.findByPracticeIdAndCode(invoice.practiceId(), "SUPPLIES").or(
                () -> categories.findByPracticeIdAndCode(invoice.practiceId(), "OTHER"));
        if (category.isEmpty()) {
            log.warn("No SUPPLIES or OTHER expense category for practice {}: vendor invoice {} booked no expense",
                    invoice.practiceId(), invoice.number());
            return;
        }
        expenses.save(Expense.builder().practiceId(invoice.practiceId()).expenseDate(invoice.invoiceDate())
                .categoryId(category.get().getId()).payee(invoice.supplierName())
                .description("Facture fournisseur " + invoice.number()).amount(invoice.amount())
                .dueDate(dueDate(invoice.invoiceDate(), invoice.paymentTerms())).vendorInvoiceId(invoice.id())
                .createdBy(invoice.validatedBy()).build());
    }

    @Override
    @Transactional
    public void onCancelled(UUID vendorInvoiceId) {
        expenses.findByVendorInvoiceId(vendorInvoiceId).ifPresent(e -> {
            if (e.getStatus() == Expense.Status.PENDING) {
                e.setStatus(Expense.Status.CANCELLED);
            } else {
                log.warn("Vendor invoice {} was cancelled but its expense is already {}; left as is", vendorInvoiceId, e.getStatus());
            }
        });
    }

    /** "30 jours", "Net 45", "30 days" → invoice date + that many days; anything else leaves it undated. */
    static LocalDate dueDate(LocalDate invoiceDate, String terms) {
        if (terms == null) {
            return null;
        }
        Matcher m = DAYS.matcher(terms);
        return m.find() ? invoiceDate.plusDays(Integer.parseInt(m.group(1))) : null;
    }
}
