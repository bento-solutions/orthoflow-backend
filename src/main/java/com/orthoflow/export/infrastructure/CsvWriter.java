package com.orthoflow.export.infrastructure;

import com.orthoflow.export.application.dto.TableExport;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * UTF-8 with a byte-order mark, because without one Excel opens the file as
 * Windows-1252 and every French accent and Arabic letter turns to mojibake.
 * Semicolon-separated: with a French or Moroccan locale Excel's list separator
 * is a semicolon, and a comma-separated file opens as a single column.
 */
public final class CsvWriter {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private CsvWriter() {
    }

    public static byte[] write(TableExport table) {
        StringBuilder out = new StringBuilder();
        line(out, table.columns().stream().map(TableExport.Column::header).toList());
        for (List<Object> row : table.rows()) {
            line(out, row.stream().map(Cells::text).toList());
        }
        if (table.totals() != null) {
            line(out, table.totals().stream().map(Cells::text).toList());
        }
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, result, 0, BOM.length);
        System.arraycopy(body, 0, result, BOM.length, body.length);
        return result;
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(';');
            out.append(escape(cells.get(i)));
        }
        out.append("\r\n");
    }

    /** Quotes a cell that needs it, and defuses spreadsheet formula injection from user-entered text. */
    static String escape(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 && !looksNumeric(value)) {
            value = "'" + value;
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static boolean looksNumeric(String value) {
        try {
            Double.parseDouble(value.replace(',', '.'));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
