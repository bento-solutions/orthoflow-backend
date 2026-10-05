package com.orthoflow.finance.application.service;

import com.orthoflow.billing.domain.model.PaymentMethod;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.dto.TableExport.Column;
import com.orthoflow.finance.application.dto.FinanceDtos.*;
import com.orthoflow.finance.infrastructure.FinanceQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Collections, the debt list, the financial dashboard and the daily cash close. */
@Service
@RequiredArgsConstructor
public class FinanceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final FinanceQuery query;
    private final JdbcTemplate jdbc;
    private final PracticeZone practiceZone;
    private final ObjectProvider<RetrocessionFigures> retrocessions;

    @Transactional(readOnly = true)
    public CollectionsReport collections(UUID practiceId, LocalDate from, LocalDate to, PaymentMethod method, UUID practitionerId) {
        checkRange(from, to);
        List<CollectionRow> rows = query.receipts(practiceId, from, to, method, practitionerId);
        Map<PaymentMethod, List<CollectionRow>> byMethod = rows.stream().collect(Collectors.groupingBy(CollectionRow::method,
                () -> new EnumMap<>(PaymentMethod.class), Collectors.toList()));
        List<MethodTotal> totals = byMethod.entrySet().stream().map(e -> new MethodTotal(e.getKey(),
                e.getValue().stream().map(CollectionRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add), e.getValue().size())).toList();
        BigDecimal received = rows.stream().map(CollectionRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CollectionsReport(from, to, received, query.allocated(practiceId, from, to, false), query.allocated(practiceId, from, to, true),
                query.creditAvailable(practiceId), totals, rows);
    }

    @Transactional(readOnly = true)
    public DebtSummary debts(UUID practiceId, LocalDate from, LocalDate to, boolean debtOnly, String search, String sort) {
        List<DebtRow> rows = query.debts(practiceId, from, to, debtOnly, search, sort);
        BigDecimal fees = rows.stream().map(DebtRow::fees).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = rows.stream().map(DebtRow::paid).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal owed = rows.stream().map(DebtRow::balance).filter(b -> b.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(DebtRow::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DebtSummary(fees, paid, owed, credit, rows.stream().filter(r -> r.balance().signum() > 0).count(), rows);
    }

    public TableExport debtTable(DebtSummary summary, LocalDate from, LocalDate to, String lang) {
        boolean en = "en".equals(lang);
        boolean ar = "ar".equals(lang);
        List<Column> columns = List.of(Column.text("Code"), Column.text(en ? "Patient" : ar ? "المريض" : "Patient"),
                Column.text(en ? "Phone" : ar ? "الهاتف" : "Téléphone"), Column.number(en ? "Fees" : ar ? "الأتعاب" : "Honoraires"),
                Column.number(en ? "Paid" : ar ? "المدفوع" : "Réglé"), Column.number(en ? "Balance" : ar ? "الباقي" : "Reste dû"),
                Column.number(en ? "Credit" : ar ? "الرصيد" : "Avoir"), Column.text(en ? "Last payment" : ar ? "آخر دفعة" : "Dernier règlement"));
        List<List<Object>> rows = summary.rows().stream().map(r -> List.<Object>of(nz(r.patientCode()), r.lastName().toUpperCase() + " " + r.firstName(),
                nz(r.phone()), r.fees(), r.paid(), r.balance(), r.credit(), r.lastPayment() == null ? "" : r.lastPayment())).toList();
        return new TableExport(en ? "Patient balances" : ar ? "أرصدة المرضى" : "Situation des patients",
                from == null ? null : from + " → " + to, columns, rows,
                List.of("Total", "", "", summary.totalFees(), summary.totalPaid(), summary.totalOwed(), summary.totalCredit(), ""));
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(UUID practiceId, LocalDate from, LocalDate to) {
        checkRange(from, to);
        List<Breakdown> byCategory = query.expensesByCategory(practiceId, from, to);
        BigDecimal salaries = sumKind(byCategory, "SALARY");
        BigDecimal social = sumKind(byCategory, "SOCIAL");
        BigDecimal all = byCategory.stream().map(Breakdown::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal operating = all.subtract(salaries).subtract(social);
        BigDecimal retro = retrocessions.stream().findFirst().map(r -> r.owedFor(practiceId, from, to)).orElse(ZERO);
        BigDecimal collected = query.collected(practiceId, from, to);
        List<Breakdown> cleaned = byCategory.stream().map(b -> new Breakdown(b.key().split("\\|")[0], b.label(), b.amount())).toList();
        return new Dashboard(from, to, query.production(practiceId, from, to), collected, query.totalOwed(practiceId),
                operating, salaries, social, ZERO, retro, collected.subtract(all).subtract(retro),
                query.collectionsByMethod(practiceId, from, to), cleaned, query.productionByPractitioner(practiceId, from, to),
                query.collectionsByPractitioner(practiceId, from, to), query.trend(practiceId, from, to));
    }

    public TableExport dashboardTable(Dashboard d, String lang) {
        boolean en = "en".equals(lang);
        boolean ar = "ar".equals(lang);
        String metric = en ? "Metric" : ar ? "المؤشر" : "Indicateur";
        String value = en ? "Amount" : ar ? "المبلغ" : "Montant";
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of(en ? "Production (invoiced)" : ar ? "الإنتاج" : "Production (facturé)", d.production()));
        rows.add(List.of(en ? "Collections (received)" : ar ? "المقبوضات" : "Encaissements", d.collections()));
        rows.add(List.of(en ? "Patient debt (current)" : ar ? "ديون المرضى" : "Créances patients (actuelles)", d.patientDebt()));
        rows.add(List.of(en ? "Operating expenses" : ar ? "مصاريف التشغيل" : "Charges d'exploitation", d.operatingExpenses()));
        rows.add(List.of(en ? "Salaries" : ar ? "الأجور" : "Salaires", d.salaries()));
        rows.add(List.of(en ? "Social charges (CNSS)" : ar ? "الضمان الاجتماعي" : "Charges sociales (CNSS)", d.socialCharges()));
        rows.add(List.of(en ? "Retrocessions" : ar ? "الاستردادات" : "Rétrocessions", d.retrocessions()));
        rows.add(List.of(en ? "Result" : ar ? "النتيجة" : "Résultat", d.result()));
        d.collectionsByMethod().forEach(b -> rows.add(List.of("  " + (en ? "Collected by " : ar ? "مقبوض ب" : "Encaissé par ") + b.label(), b.amount())));
        d.expensesByCategory().forEach(b -> rows.add(List.of("  " + b.label(), b.amount())));
        return new TableExport(en ? "Financial dashboard" : ar ? "اللوحة المالية" : "Tableau de bord financier", d.from() + " → " + d.to(),
                List.of(Column.text(metric), Column.number(value)), rows.stream().map(r -> List.<Object>of(r.get(0), r.get(1))).toList());
    }

    // ── Daily cash close ──
    @Transactional(readOnly = true)
    public CashClosing cashClosing(UUID practiceId, LocalDate day) {
        Map<PaymentMethod, BigDecimal> expected = query.expectedByMethod(practiceId, day);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id, notes, created_at FROM cash_closings WHERE practice_id = ? AND closing_date = ?", practiceId, day);
        if (existing.isEmpty()) {
            List<CashLine> lines = expected.entrySet().stream().map(e -> new CashLine(e.getKey(), e.getValue(), null, null)).toList();
            return new CashClosing(null, day, false, lines, ZERO, null, null);
        }
        UUID id = (UUID) existing.get(0).get("id");
        List<CashLine> lines = jdbc.query("SELECT method, expected, counted FROM cash_closing_lines WHERE closing_id = ? ORDER BY method",
                (rs, i) -> new CashLine(PaymentMethod.valueOf(rs.getString("method")), rs.getBigDecimal("expected"), rs.getBigDecimal("counted"),
                        rs.getBigDecimal("counted").subtract(rs.getBigDecimal("expected"))), id);
        BigDecimal diff = lines.stream().map(CashLine::difference).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CashClosing(id, day, true, lines, diff, (String) existing.get(0).get("notes"),
                ((java.sql.Timestamp) existing.get(0).get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC));
    }

    /**
     * Closes a day: the expected takings per method are computed here from the
     * receipts, never taken from the request, and compared with what was counted.
     * A closed day is final — it is a record, not a draft.
     */
    @Transactional
    public CashClosing close(UUID practiceId, UUID actorId, CloseCash r) {
        LocalDate today = LocalDate.now(practiceZone.of(practiceId));
        if (r.date().isAfter(today)) {
            throw new ValidationException("A day that has not happened yet cannot be closed");
        }
        if (!jdbc.queryForList("SELECT 1 FROM cash_closings WHERE practice_id = ? AND closing_date = ?", practiceId, r.date()).isEmpty()) {
            throw new ConflictException("This day is already closed");
        }
        Map<PaymentMethod, BigDecimal> expected = query.expectedByMethod(practiceId, r.date());
        Map<PaymentMethod, BigDecimal> counted = new EnumMap<>(PaymentMethod.class);
        for (Counted c : r.counts()) {
            if (counted.put(c.method(), c.counted()) != null) {
                throw new ValidationException("Method " + c.method() + " is counted twice");
            }
        }
        Set<PaymentMethod> methods = new TreeSet<>(expected.keySet());
        methods.addAll(counted.keySet());
        for (PaymentMethod expectedMethod : expected.keySet()) {
            if (!counted.containsKey(expectedMethod)) {
                throw new ValidationException("Count " + expectedMethod + ": " + expected.get(expectedMethod) + " was expected");
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cash_closings (id, practice_id, closing_date, closed_by, notes) VALUES (?, ?, ?, ?, ?)",
                id, practiceId, r.date(), actorId, r.notes());
        for (PaymentMethod m : methods) {
            jdbc.update("INSERT INTO cash_closing_lines (id, closing_id, method, expected, counted) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), id, m.name(), expected.getOrDefault(m, ZERO), counted.getOrDefault(m, ZERO));
        }
        return cashClosing(practiceId, r.date());
    }

    private static BigDecimal sumKind(List<Breakdown> byCategory, String kind) {
        return byCategory.stream().filter(b -> b.key().endsWith("|" + kind)).map(Breakdown::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ValidationException("Give a period: from must not be after to");
        }
        if (from.plusYears(5).isBefore(to)) {
            throw new ValidationException("A period is limited to five years");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
