package com.orthoflow.export.application.dto;

import java.util.List;

/**
 * A report as a table, which is what almost every Denteam screen exports. One
 * description renders to PDF, Excel or CSV so a report is written once. Cells
 * are plain objects: numbers and dates stay typed in Excel and are formatted
 * for the PDF and CSV.
 */
public record TableExport(String title, String subtitle, List<Column> columns, List<List<Object>> rows,
                          List<Object> totals) {

    public enum Align { LEFT, RIGHT, CENTER }

    public record Column(String header, Align align) {
        public static Column text(String header) {
            return new Column(header, Align.LEFT);
        }

        public static Column number(String header) {
            return new Column(header, Align.RIGHT);
        }
    }

    public TableExport(String title, String subtitle, List<Column> columns, List<List<Object>> rows) {
        this(title, subtitle, columns, rows, null);
    }
}
