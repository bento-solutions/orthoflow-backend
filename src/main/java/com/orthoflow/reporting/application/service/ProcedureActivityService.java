package com.orthoflow.reporting.application.service;

import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.dto.TableExport.Column;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.*;
import com.orthoflow.reporting.infrastructure.ReportingQuery;
import com.orthoflow.reporting.infrastructure.ReportingQuery.ProcedureAgg;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.treatment.domain.model.TreatmentInvoiceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * What the clinic did and what it earned from it, by treatment, category,
 * practitioner and status. It reads the same finalised treatment sessions as the
 * stock module's profitability view and adds the dimensions that view lacks; the
 * practitioner comes from the patient invoice the session produced.
 */
@Service
@RequiredArgsConstructor
public class ProcedureActivityService {

    private final ReportingQuery query;

    @Transactional(readOnly = true)
    public ProcedureActivity activity(UUID practiceId, LocalDate from, LocalDate to, List<TreatmentInvoiceStatus> statuses,
                                      UUID practitionerId, String category) {
        checkRange(from, to);
        List<String> wanted = statuses == null || statuses.isEmpty()
                ? List.of(TreatmentInvoiceStatus.FINALIZED.name()) : statuses.stream().map(Enum::name).toList();
        List<ProcedureAgg> aggregates = query.procedures(practiceId, from, to, wanted, practitionerId, category);

        List<ProcedureRow> rows = aggregates.stream().map(a -> {
            BigDecimal margin = a.revenue().subtract(a.material());
            return new ProcedureRow(a.treatmentId(), a.name(), a.category(), a.practitionerId(), a.practitionerName(), a.status(),
                    a.sessions(), a.revenue(), a.material(), margin, percent(margin, a.revenue()));
        }).toList();

        return new ProcedureActivity(from, to, rows, total("*", "Total", rows),
                group(rows, r -> r.category(), r -> r.category().isEmpty() ? "—" : r.category()),
                group(rows, r -> r.practitionerId() == null ? "-" : r.practitionerId().toString(),
                        r -> r.practitionerName() == null ? "—" : r.practitionerName()));
    }

    public TableExport table(ProcedureActivity a, String lang) {
        boolean en = "en".equals(lang);
        boolean ar = "ar".equals(lang);
        List<Column> columns = List.of(
                Column.text(en ? "Procedure" : ar ? "الإجراء" : "Acte"),
                Column.text(en ? "Category" : ar ? "الفئة" : "Catégorie"),
                Column.text(en ? "Practitioner" : ar ? "الطبيب" : "Praticien"),
                Column.text(en ? "Status" : ar ? "الحالة" : "Statut"),
                Column.number(en ? "Sessions" : ar ? "الجلسات" : "Séances"),
                Column.number(en ? "Revenue" : ar ? "الإيرادات" : "Chiffre d'affaires"),
                Column.number(en ? "Material cost" : ar ? "كلفة المواد" : "Coût matières"),
                Column.number(en ? "Margin" : ar ? "الهامش" : "Marge"),
                Column.number(en ? "Margin %" : ar ? "الهامش %" : "Marge %"));
        List<List<Object>> rows = a.rows().stream().map(r -> List.<Object>of(r.treatmentName(), r.category(),
                r.practitionerName() == null ? "—" : r.practitionerName(), r.status(), r.sessions(), r.revenue(), r.materialCost(),
                r.grossMargin(), r.marginPercent())).toList();
        GroupTotal t = a.total();
        return new TableExport(en ? "Procedure activity" : ar ? "نشاط الإجراءات" : "Activité par acte", a.from() + " → " + a.to(), columns, rows,
                List.of("Total", "", "", "", t.sessions(), t.revenue(), t.materialCost(), t.grossMargin(), percent(t.grossMargin(), t.revenue())));
    }

    private static List<GroupTotal> group(List<ProcedureRow> rows, java.util.function.Function<ProcedureRow, String> key,
                                          java.util.function.Function<ProcedureRow, String> label) {
        Map<String, List<ProcedureRow>> grouped = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (ProcedureRow r : rows) {
            grouped.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
            labels.putIfAbsent(key.apply(r), label.apply(r));
        }
        return grouped.entrySet().stream().map(e -> total(e.getKey(), labels.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(GroupTotal::revenue).reversed()).toList();
    }

    private static GroupTotal total(String key, String label, List<ProcedureRow> rows) {
        BigDecimal revenue = rows.stream().map(ProcedureRow::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal material = rows.stream().map(ProcedureRow::materialCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new GroupTotal(key, label, rows.stream().mapToLong(ProcedureRow::sessions).sum(), revenue, material, revenue.subtract(material));
    }

    static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO : part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }

    static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ValidationException("Give a period: from must not be after to");
        }
        if (from.plusYears(5).isBefore(to)) {
            throw new ValidationException("A period is limited to five years");
        }
    }
}
