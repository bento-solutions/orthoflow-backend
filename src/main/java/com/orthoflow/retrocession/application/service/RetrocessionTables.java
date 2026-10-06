package com.orthoflow.retrocession.application.service;

import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.dto.TableExport.Column;
import com.orthoflow.retrocession.application.dto.RetrocessionDtos.PractitionerFigures;
import com.orthoflow.retrocession.application.dto.RetrocessionDtos.Simulation;

import java.math.BigDecimal;
import java.util.List;

/** The simulation as a table, for the pdf, xlsx and csv exports. */
public final class RetrocessionTables {

    private RetrocessionTables() {
    }

    public static TableExport simulation(Simulation s, String lang) {
        boolean en = "en".equals(lang);
        boolean ar = "ar".equals(lang);
        List<Column> columns = List.of(
                Column.text(en ? "Practitioner" : ar ? "الطبيب" : "Praticien"),
                Column.number(en ? "Base" : ar ? "الأساس" : "Base"),
                Column.number(en ? "Percentage" : ar ? "النسبة" : "Pourcentage"),
                Column.number(en ? "Lab fees" : ar ? "المختبر" : "Frais labo"),
                Column.number(en ? "Fixed" : ar ? "ثابت" : "Fixe"),
                Column.number(en ? "Adjustment" : ar ? "تسوية" : "Ajustement"),
                Column.number(en ? "Total due" : ar ? "المستحق" : "Total dû"),
                Column.number(en ? "Advances" : ar ? "دفعات مقدمة" : "Avances"),
                Column.number(en ? "Net payable" : ar ? "الصافي" : "Net à payer"));
        List<List<Object>> rows = s.practitioners().stream().map(RetrocessionTables::row).toList();
        BigDecimal base = sum(s, PractitionerFigures::base);
        return new TableExport(en ? "Retrocession simulation" : ar ? "محاكاة الاستردادات" : "Simulation des rétrocessions",
                s.from() + " → " + s.to(), columns, rows,
                List.of("Total", base, sum(s, PractitionerFigures::variable), sum(s, PractitionerFigures::labDeduction),
                        sum(s, PractitionerFigures::fixed), sum(s, PractitionerFigures::adjustment), s.totalGross(),
                        sum(s, PractitionerFigures::advancesApplied), s.totalNet()));
    }

    private static List<Object> row(PractitionerFigures f) {
        return List.of(f.practitionerName() == null ? "—" : f.practitionerName(), f.base(), f.variable(), f.labDeduction(), f.fixed(),
                f.adjustment(), f.gross(), f.advancesApplied(), f.net());
    }

    private static BigDecimal sum(Simulation s, java.util.function.Function<PractitionerFigures, BigDecimal> f) {
        return s.practitioners().stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
