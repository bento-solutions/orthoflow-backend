package com.orthoflow.export.infrastructure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Turns a typed cell into the text the PDF and CSV show. */
final class Cells {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private Cells() {
    }

    static String text(Object cell) {
        if (cell == null) return "";
        if (cell instanceof BigDecimal d) return money(d);
        if (cell instanceof Double || cell instanceof Float) return money(BigDecimal.valueOf(((Number) cell).doubleValue()));
        if (cell instanceof LocalDate d) return DATE.format(d);
        if (cell instanceof OffsetDateTime d) return DATE_TIME.format(d);
        if (cell instanceof Boolean b) return b ? "✓" : "";
        return String.valueOf(cell);
    }

    /** 1 234,50 — the French grouping a Moroccan accountant reads without a second look. */
    static String money(BigDecimal value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.00", symbols).format(value.setScale(2, RoundingMode.HALF_UP));
    }
}
