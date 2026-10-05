package com.orthoflow.finance.application.service;

import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.dto.TableExport.Column;
import com.orthoflow.finance.application.dto.ExpenseDtos.*;
import com.orthoflow.finance.domain.model.Expense;
import com.orthoflow.finance.domain.model.ExpenseCategory;
import com.orthoflow.finance.infrastructure.ExpenseCategoryJpaRepository;
import com.orthoflow.finance.infrastructure.ExpenseJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseJpaRepository expenses;
    private final ExpenseCategoryJpaRepository categories;
    private final PracticeZone practiceZone;
    private final LiveEventPublisher liveEvents;

    @Transactional(readOnly = true)
    public List<View> list(UUID practiceId, LocalDate from, LocalDate to, UUID categoryId, Expense.Status status, String search) {
        String like = search == null || search.isBlank() ? null
                : "%" + search.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return views(practiceId, expenses.search(practiceId, from, to, categoryId, status, like));
    }

    @Transactional(readOnly = true)
    public View get(UUID practiceId, UUID id) {
        return views(practiceId, List.of(require(practiceId, id))).get(0);
    }

    @Transactional
    public View create(UUID practiceId, UUID actorId, Request r) {
        requireCategory(practiceId, r.categoryId());
        Expense.Recurrence recurrence = r.recurrence() == null ? Expense.Recurrence.NONE : r.recurrence();
        Expense e = Expense.builder().practiceId(practiceId).expenseDate(r.expenseDate()).categoryId(r.categoryId())
                .payee(blankToNull(r.payee())).description(blankToNull(r.description())).amount(r.amount())
                .dueDate(r.dueDate()).recurrence(recurrence).notes(r.notes()).createdBy(actorId).build();
        if (recurrence != Expense.Recurrence.NONE) {
            e.setRecurrenceNext(next(r.expenseDate(), recurrence));
        }
        Expense saved = expenses.save(e);
        liveEvents.publish(practiceId, "finance", saved.getId());
        return views(practiceId, List.of(saved)).get(0);
    }

    @Transactional
    public View update(UUID practiceId, UUID id, Request r) {
        Expense e = require(practiceId, id);
        if (e.getStatus() == Expense.Status.CANCELLED) {
            throw new ConflictException("A cancelled expense cannot be changed");
        }
        requireCategory(practiceId, r.categoryId());
        e.setExpenseDate(r.expenseDate());
        e.setCategoryId(r.categoryId());
        e.setPayee(blankToNull(r.payee()));
        e.setDescription(blankToNull(r.description()));
        e.setAmount(r.amount());
        e.setDueDate(r.dueDate());
        e.setNotes(r.notes());
        Expense.Recurrence recurrence = r.recurrence() == null ? e.getRecurrence() : r.recurrence();
        if (recurrence != e.getRecurrence()) {
            e.setRecurrence(recurrence);
            e.setRecurrenceNext(recurrence == Expense.Recurrence.NONE ? null : next(LocalDate.now(practiceZone.of(practiceId)), recurrence));
        }
        liveEvents.publish(practiceId, "finance", id);
        return views(practiceId, List.of(expenses.save(e))).get(0);
    }

    @Transactional
    public View markPaid(UUID practiceId, UUID id, MarkPaid r) {
        Expense e = require(practiceId, id);
        if (e.getStatus() != Expense.Status.PENDING) {
            throw new ConflictException("Only a pending expense can be marked paid (it is " + e.getStatus() + ")");
        }
        e.setStatus(Expense.Status.PAID);
        e.setPaidDate(r.paidDate() != null ? r.paidDate() : LocalDate.now(practiceZone.of(practiceId)));
        e.setMethod(r.method());
        liveEvents.publish(practiceId, "finance", id);
        return views(practiceId, List.of(e)).get(0);
    }

    @Transactional
    public View cancel(UUID practiceId, UUID id) {
        Expense e = require(practiceId, id);
        if (e.getStatus() == Expense.Status.PAID) {
            throw new ConflictException("A paid expense cannot be cancelled; correct it instead");
        }
        e.setStatus(Expense.Status.CANCELLED);
        e.setRecurrenceNext(null);
        liveEvents.publish(practiceId, "finance", id);
        return views(practiceId, List.of(e)).get(0);
    }

    @Transactional
    public void attachReceipt(UUID practiceId, UUID id, UUID fileId) {
        require(practiceId, id).setReceiptFileId(fileId);
    }

    // ── Categories ──
    @Transactional(readOnly = true)
    public List<CategoryView> categories(UUID practiceId) {
        return categories.findByPracticeIdOrderByDisplayOrderAscNameFrAsc(practiceId).stream().map(CategoryView::from).toList();
    }

    @Transactional
    public CategoryView createCategory(UUID practiceId, CategoryRequest r) {
        String code = r.code() != null && !r.code().isBlank() ? r.code() : uniqueCode(practiceId, r.nameEn());
        if (categories.existsByPracticeIdAndCode(practiceId, code)) {
            throw new ConflictException("A category with code " + code + " already exists");
        }
        ExpenseCategory c = ExpenseCategory.builder().practiceId(practiceId).code(code).nameFr(r.nameFr().trim())
                .nameEn(r.nameEn().trim()).nameAr(r.nameAr().trim()).kind(r.kind() == null ? ExpenseCategory.Kind.OPERATING : r.kind())
                .active(r.active() == null || r.active()).displayOrder(r.displayOrder() == null ? 50 : r.displayOrder()).build();
        return CategoryView.from(categories.save(c));
    }

    @Transactional
    public CategoryView updateCategory(UUID practiceId, UUID id, CategoryRequest r) {
        ExpenseCategory c = requireCategory(practiceId, id);
        c.setNameFr(r.nameFr().trim());
        c.setNameEn(r.nameEn().trim());
        c.setNameAr(r.nameAr().trim());
        if (r.kind() != null) c.setKind(r.kind());
        if (r.active() != null) c.setActive(r.active());
        if (r.displayOrder() != null) c.setDisplayOrder(r.displayOrder());
        return CategoryView.from(categories.save(c));
    }

    /** The expenses as a report, in the language asked for. */
    public TableExport table(List<View> rows, String lang, LocalDate from, LocalDate to) {
        String l = lang == null ? "fr" : lang;
        boolean ar = "ar".equals(l);
        boolean en = "en".equals(l);
        List<Column> columns = List.of(Column.text(en ? "Date" : ar ? "التاريخ" : "Date"), Column.text(en ? "Category" : ar ? "الفئة" : "Catégorie"),
                Column.text(en ? "Payee" : ar ? "المستفيد" : "Bénéficiaire"), Column.text(en ? "Description" : ar ? "الوصف" : "Description"),
                Column.number(en ? "Amount" : ar ? "المبلغ" : "Montant"), Column.text(en ? "Status" : ar ? "الحالة" : "Statut"),
                Column.text(en ? "Paid on" : ar ? "تاريخ الأداء" : "Payé le"));
        List<List<Object>> data = rows.stream().filter(v -> v.status() != Expense.Status.CANCELLED).map(v -> List.<Object>of(
                v.expenseDate(), v.categoryName(), nz(v.payee()), nz(v.description()), v.amount(), v.status().name(),
                v.paidDate() == null ? "" : v.paidDate())).toList();
        BigDecimal total = data.stream().map(r -> (BigDecimal) r.get(4)).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TableExport(en ? "Expenses" : ar ? "المصاريف" : "Dépenses", from + " → " + to, columns, data,
                List.of("Total", "", "", "", total, "", ""));
    }

    private List<View> views(UUID practiceId, List<Expense> rows) {
        Map<UUID, ExpenseCategory> byId = categories.findByPracticeIdOrderByDisplayOrderAscNameFrAsc(practiceId).stream()
                .collect(Collectors.toMap(ExpenseCategory::getId, Function.identity()));
        LocalDate today = LocalDate.now(practiceZone.of(practiceId));
        return rows.stream().map(e -> {
            ExpenseCategory c = byId.get(e.getCategoryId());
            return new View(e.getId(), e.getExpenseDate(), e.getCategoryId(), c == null ? null : c.getNameFr(),
                    c == null ? null : c.getKind(), e.getPayee(), e.getDescription(), e.getAmount(), e.getDueDate(),
                    e.getPaidDate(), e.getStatus(), e.getMethod(), e.getReceiptFileId(), e.getVendorInvoiceId(), e.getLabOrderId(),
                    e.getRecurrence(), e.getRecurrenceNext(), e.getNotes(),
                    e.getStatus() == Expense.Status.PENDING && e.getDueDate() != null && e.getDueDate().isBefore(today));
        }).toList();
    }

    Expense require(UUID practiceId, UUID id) {
        return expenses.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Expense not found"));
    }

    private ExpenseCategory requireCategory(UUID practiceId, UUID id) {
        return categories.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Expense category not found"));
    }

    static LocalDate next(LocalDate from, Expense.Recurrence recurrence) {
        return switch (recurrence) {
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case YEARLY -> from.plusYears(1);
            case NONE -> throw new ValidationException("No recurrence");
        };
    }

    private String uniqueCode(UUID practiceId, String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        base = base.isEmpty() ? "CATEGORY" : base.substring(0, Math.min(base.length(), 30));
        String code = base;
        for (int i = 2; categories.existsByPracticeIdAndCode(practiceId, code); i++) {
            code = base + "_" + i;
        }
        return code;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
