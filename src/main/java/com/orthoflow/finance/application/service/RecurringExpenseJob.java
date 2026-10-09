package com.orthoflow.finance.application.service;

import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.finance.domain.model.Expense;
import com.orthoflow.finance.infrastructure.ExpenseJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * Rent comes every month. A recurring expense is a template; each day this
 * creates the copies whose date has come and moves the template on, catching up
 * month by month if the application was down. Each copy is its own transaction,
 * so one bad row does not hold back the rest.
 */
@Component
public class RecurringExpenseJob {

    private static final Logger log = LoggerFactory.getLogger(RecurringExpenseJob.class);

    private final ExpenseJpaRepository expenses;
    private final TransactionTemplate tx;
    private final Tenancy tenancy;

    public RecurringExpenseJob(ExpenseJpaRepository expenses, TransactionTemplate tx, Tenancy tenancy) {
        this.expenses = expenses;
        this.tx = tx;
        this.tenancy = tenancy;
    }

    @Scheduled(cron = "${orthoflow.finance.recurring-cron:0 15 4 * * *}")
    public void run() {
        int created = generate(LocalDate.now(java.time.ZoneId.of("Africa/Casablanca")));
        if (created > 0) {
            log.info("Generated {} recurring expense(s)", created);
        }
    }

    /** Generates every copy due on or before {@code today}, in every clinic; returns how many were created. */
    public int generate(LocalDate today) {
        int created = 0;
        List<Expense> templates = tenancy.callAcrossClinics(() -> expenses.recurringDue(today));
        for (Expense template : templates) {
            try {
                Integer n = tenancy.callAs(template.getPracticeId(), () -> tx.execute(s -> copiesFor(template.getId(), today)));
                created += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.error("Could not generate the recurring expense {}", template.getId(), e);
            }
        }
        return created;
    }

    private int copiesFor(java.util.UUID templateId, LocalDate today) {
        Expense template = expenses.findById(templateId).orElse(null);
        if (template == null || template.getRecurrenceNext() == null) {
            return 0;
        }
        int n = 0;
        while (!template.getRecurrenceNext().isAfter(today) && n < 24) {
            LocalDate date = template.getRecurrenceNext();
            expenses.save(Expense.builder().practiceId(template.getPracticeId()).expenseDate(date).categoryId(template.getCategoryId())
                    .payee(template.getPayee()).description(template.getDescription()).amount(template.getAmount())
                    .dueDate(date).recurrenceParentId(template.getId()).notes(template.getNotes()).createdBy(template.getCreatedBy()).build());
            template.setRecurrenceNext(ExpenseService.next(date, template.getRecurrence()));
            n++;
        }
        return n;
    }
}
