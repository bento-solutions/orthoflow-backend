package com.orthoflow.reporting.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.dto.TableExport.Column;
import com.orthoflow.finance.application.service.RetrocessionFigures;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.*;
import com.orthoflow.reporting.infrastructure.ReportingQuery;
import com.orthoflow.reporting.infrastructure.ReportingQuery.DayAmount;
import com.orthoflow.reporting.infrastructure.ReportingQuery.ExpenseDay;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * The Moroccan CPC (Compte de Produits et Charges) for a clinic, grouped by day,
 * week, month or year. It is the operating section only: fees on one side, the
 * clinic's expenses and collaborator retrocessions on the other, and the operating
 * result between them. Depreciation, financial and non-recurring items and
 * corporate tax are not tracked anywhere in the application, and the statement
 * says so rather than showing a zero that looks like a finding.
 *
 * <p>Fees are either what was collected (cash basis, the default and what the
 * financial dashboard calls collections) or what was invoiced; expenses are
 * dated by their expense date, as in the dashboard, so the two screens agree.
 */
@Service
@RequiredArgsConstructor
public class IncomeStatementService {

    private static final int MAX_COLUMNS = 400;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ReportingQuery query;
    private final ObjectProvider<RetrocessionFigures> retrocessions;

    @Transactional(readOnly = true)
    public IncomeStatement statement(UUID practiceId, LocalDate from, LocalDate to, Group group, String basis, boolean includeRetrocessions, String lang) {
        ProcedureActivityService.checkRange(from, to);
        String language = "ar".equals(lang) || "en".equals(lang) ? lang : "fr";
        boolean produced = "PRODUCED".equalsIgnoreCase(basis);
        Group g = group == null ? Group.MONTH : group;
        List<Period> periods = periods(from, to, g);
        if (periods.size() > MAX_COLUMNS) {
            throw new ValidationException("That would be " + periods.size() + " columns; choose a coarser grouping or a shorter period");
        }
        Buckets buckets = new Buckets(periods);
        Labels l = Labels.of(language);

        // Revenue
        BigDecimal[] fees = buckets.empty();
        for (DayAmount a : produced ? query.producedByDay(practiceId, from, to) : query.collectedByDay(practiceId, from, to)) {
            buckets.add(fees, a.day(), a.amount());
        }

        // Expenses by group and category
        Map<String, Map<String, BigDecimal[]>> byGroup = new LinkedHashMap<>();
        Map<String, String> categoryNames = new HashMap<>();
        Map<String, Integer> categoryOrder = new HashMap<>();
        for (ExpenseDay e : query.expensesByDay(practiceId, from, to, language)) {
            String groupKey = groupOf(e.kind());
            Map<String, BigDecimal[]> cats = byGroup.computeIfAbsent(groupKey, k -> new LinkedHashMap<>());
            buckets.add(cats.computeIfAbsent(e.code(), k -> buckets.empty()), e.day(), e.amount());
            categoryNames.put(e.code(), e.name());
            categoryOrder.put(e.code(), e.order());
        }

        BigDecimal[] retro = buckets.empty();
        boolean hasRetro = false;
        if (includeRetrocessions) {
            RetrocessionFigures figures = retrocessions.getIfAvailable();
            if (figures != null) {
                for (Map.Entry<LocalDate, BigDecimal> e : figures.accruedByDay(practiceId, from, to).entrySet()) {
                    buckets.add(retro, e.getKey(), e.getValue());
                }
                hasRetro = true;
            }
        }

        List<StatementRow> rows = new ArrayList<>();
        rows.add(row("I", l.get("revenue"), RowKind.HEADING, 0, null));
        rows.add(row("fees", produced ? l.get("feesProduced") : l.get("feesCollected"), RowKind.LINE, 1, fees));
        rows.add(row("I.total", l.get("totalRevenue"), RowKind.SUBTOTAL, 0, fees));

        rows.add(row("II", l.get("expenses"), RowKind.HEADING, 0, null));
        List<BigDecimal[]> expenseTotals = new ArrayList<>();
        for (String groupKey : List.of("PURCHASES", "EXTERNAL", "TAX", "PERSONNEL")) {
            Map<String, BigDecimal[]> cats = byGroup.getOrDefault(groupKey, Map.of());
            List<BigDecimal[]> parts = new ArrayList<>(cats.values());
            if ("EXTERNAL".equals(groupKey) && hasRetro) {
                parts.add(retro);
            }
            BigDecimal[] groupTotal = sum(buckets, parts);
            rows.add(row(groupKey, l.get(groupKey), RowKind.LINE, 1, groupTotal));
            cats.entrySet().stream().sorted(Comparator.comparing((Map.Entry<String, BigDecimal[]> e) -> categoryOrder.get(e.getKey()))
                            .thenComparing(e -> categoryNames.get(e.getKey())))
                    .forEach(e -> rows.add(row(e.getKey(), categoryNames.get(e.getKey()), RowKind.LINE, 2, e.getValue())));
            if ("EXTERNAL".equals(groupKey) && hasRetro) {
                rows.add(row("RETROCESSIONS", l.get("retrocessions"), RowKind.LINE, 2, retro));
            }
            expenseTotals.add(groupTotal);
        }
        BigDecimal[] expenses = sum(buckets, expenseTotals);
        rows.add(row("II.total", l.get("totalExpenses"), RowKind.SUBTOTAL, 0, expenses));

        BigDecimal[] result = buckets.empty();
        for (int i = 0; i < result.length; i++) {
            result[i] = fees[i].subtract(expenses[i]);
        }
        rows.add(row("III", l.get("result"), RowKind.RESULT, 0, result));

        List<String> notes = new ArrayList<>();
        notes.add(produced ? l.get("noteProduced") : l.get("noteCollected"));
        notes.add(l.get("noteScope"));
        if (includeRetrocessions) {
            notes.add(l.get("noteRetro"));
        }
        return new IncomeStatement(from, to, g, produced ? "PRODUCED" : "COLLECTED", includeRetrocessions, periods, rows, notes);
    }

    public TableExport table(IncomeStatement s, String lang) {
        Labels l = Labels.of("ar".equals(lang) || "en".equals(lang) ? lang : "fr");
        List<Column> columns = new ArrayList<>();
        columns.add(Column.text(l.get("title")));
        s.periods().forEach(p -> columns.add(Column.number(p.label())));
        columns.add(Column.number("Total"));
        List<List<Object>> rows = new ArrayList<>();
        for (StatementRow r : s.rows()) {
            List<Object> cells = new ArrayList<>();
            cells.add("  ".repeat(r.level() > 0 ? r.level() - 1 : 0) + r.label());
            for (int i = 0; i < s.periods().size(); i++) {
                cells.add(r.amounts() == null ? "" : r.amounts().get(i));
            }
            cells.add(r.total() == null ? "" : r.total());
            rows.add(cells);
        }
        return new TableExport(l.get("title"), s.from() + " → " + s.to(), columns, rows);
    }

    // ── Periods ──
    static List<Period> periods(LocalDate from, LocalDate to, Group group) {
        List<Period> out = new ArrayList<>();
        LocalDate start = from;
        while (!start.isAfter(to)) {
            LocalDate end = switch (group) {
                case DAY -> start;
                case WEEK -> start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                case MONTH -> start.with(TemporalAdjusters.lastDayOfMonth());
                case YEAR -> start.with(TemporalAdjusters.lastDayOfYear());
            };
            if (end.isAfter(to)) {
                end = to;
            }
            String label = switch (group) {
                case DAY -> start.toString();
                case WEEK -> start.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                case MONTH -> MONTH.format(start);
                case YEAR -> String.valueOf(start.getYear());
            };
            out.add(new Period(start.toString(), label, start, end));
            start = end.plusDays(1);
        }
        return out;
    }

    /** Puts a dated amount into the right column. */
    private static final class Buckets {
        private final List<Period> periods;
        private final TreeMap<LocalDate, Integer> starts = new TreeMap<>();

        Buckets(List<Period> periods) {
            this.periods = periods;
            for (int i = 0; i < periods.size(); i++) {
                starts.put(periods.get(i).from(), i);
            }
        }

        BigDecimal[] empty() {
            BigDecimal[] a = new BigDecimal[periods.size()];
            Arrays.fill(a, BigDecimal.ZERO);
            return a;
        }

        void add(BigDecimal[] target, LocalDate day, BigDecimal amount) {
            Map.Entry<LocalDate, Integer> e = starts.floorEntry(day);
            if (e != null && !day.isAfter(periods.get(e.getValue()).to())) {
                target[e.getValue()] = target[e.getValue()].add(amount);
            }
        }
    }

    private static BigDecimal[] sum(Buckets buckets, List<BigDecimal[]> parts) {
        BigDecimal[] out = buckets.empty();
        for (BigDecimal[] part : parts) {
            for (int i = 0; i < out.length; i++) {
                out[i] = out[i].add(part[i]);
            }
        }
        return out;
    }

    private static StatementRow row(String code, String label, RowKind kind, int level, BigDecimal[] amounts) {
        if (amounts == null) {
            return new StatementRow(code, label, kind, level, null, null);
        }
        List<BigDecimal> rounded = Arrays.stream(amounts).map(a -> a.setScale(2, java.math.RoundingMode.HALF_UP)).toList();
        return new StatementRow(code, label, kind, level, rounded, rounded.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String groupOf(String kind) {
        return switch (kind) {
            case "SUPPLIES", "LAB" -> "PURCHASES";
            case "TAX" -> "TAX";
            case "SALARY", "SOCIAL" -> "PERSONNEL";
            default -> "EXTERNAL";
        };
    }

    private record Labels(Map<String, String> words) {
        String get(String key) {
            return words.get(key);
        }

        static Labels of(String lang) {
            return new Labels(switch (lang) {
                case "en" -> Map.ofEntries(Map.entry("title", "Income statement (CPC)"), Map.entry("revenue", "I. OPERATING INCOME"),
                        Map.entry("feesCollected", "Fees collected"), Map.entry("feesProduced", "Fees invoiced"),
                        Map.entry("totalRevenue", "Total I"), Map.entry("expenses", "II. OPERATING EXPENSES"),
                        Map.entry("PURCHASES", "Materials and supplies consumed (incl. laboratory)"), Map.entry("EXTERNAL", "Other external expenses"),
                        Map.entry("TAX", "Taxes and duties"), Map.entry("PERSONNEL", "Personnel expenses"),
                        Map.entry("retrocessions", "Retrocessions to collaborators"), Map.entry("totalExpenses", "Total II"),
                        Map.entry("result", "III. OPERATING RESULT (I - II)"),
                        Map.entry("noteCollected", "Fees are the money received in the period (cash basis)."),
                        Map.entry("noteProduced", "Fees are the amounts invoiced in the period, paid or not."),
                        Map.entry("noteScope", "Depreciation, financial and non-recurring items and corporate tax are not tracked, so the result is the operating result only."),
                        Map.entry("noteRetro", "Retrocessions are a simulation from the current rules, not validated statements."));
                case "ar" -> Map.ofEntries(Map.entry("title", "حساب الإنتاج والتكاليف (CPC)"), Map.entry("revenue", "I. منتوجات الاستغلال"),
                        Map.entry("feesCollected", "الأتعاب المقبوضة"), Map.entry("feesProduced", "الأتعاب المفوترة"),
                        Map.entry("totalRevenue", "مجموع I"), Map.entry("expenses", "II. تكاليف الاستغلال"),
                        Map.entry("PURCHASES", "مشتريات المواد واللوازم المستهلكة (بما فيها المختبر)"), Map.entry("EXTERNAL", "تكاليف خارجية أخرى"),
                        Map.entry("TAX", "الضرائب والرسوم"), Map.entry("PERSONNEL", "تكاليف المستخدمين"),
                        Map.entry("retrocessions", "استردادات الأتعاب للمتعاونين"), Map.entry("totalExpenses", "مجموع II"),
                        Map.entry("result", "III. نتيجة الاستغلال (I - II)"),
                        Map.entry("noteCollected", "الأتعاب هي المبالغ المقبوضة خلال الفترة."),
                        Map.entry("noteProduced", "الأتعاب هي المبالغ المفوترة خلال الفترة سواء أديت أم لا."),
                        Map.entry("noteScope", "الاستهلاكات والعمليات المالية وغير الجارية والضريبة على الشركات غير متتبعة، فالنتيجة هي نتيجة الاستغلال فقط."),
                        Map.entry("noteRetro", "الاستردادات محاكاة وفق القواعد الحالية وليست كشوفا مصادقا عليها."));
                default -> Map.ofEntries(Map.entry("title", "Compte de produits et charges (CPC)"), Map.entry("revenue", "I. PRODUITS D'EXPLOITATION"),
                        Map.entry("feesCollected", "Honoraires encaissés"), Map.entry("feesProduced", "Honoraires facturés"),
                        Map.entry("totalRevenue", "Total I"), Map.entry("expenses", "II. CHARGES D'EXPLOITATION"),
                        Map.entry("PURCHASES", "Achats consommés de matières et fournitures (dont laboratoire)"), Map.entry("EXTERNAL", "Autres charges externes"),
                        Map.entry("TAX", "Impôts et taxes"), Map.entry("PERSONNEL", "Charges de personnel"),
                        Map.entry("retrocessions", "Rétrocessions aux collaborateurs"), Map.entry("totalExpenses", "Total II"),
                        Map.entry("result", "III. RÉSULTAT D'EXPLOITATION (I - II)"),
                        Map.entry("noteCollected", "Les honoraires sont les sommes encaissées dans la période (base caisse)."),
                        Map.entry("noteProduced", "Les honoraires sont les montants facturés dans la période, réglés ou non."),
                        Map.entry("noteScope", "Les dotations, les éléments financiers et non courants et l'impôt sur les sociétés ne sont pas suivis : le résultat est le résultat d'exploitation uniquement."),
                        Map.entry("noteRetro", "Les rétrocessions sont une simulation d'après les règles actuelles, pas des relevés validés."));
            });
        }
    }
}
