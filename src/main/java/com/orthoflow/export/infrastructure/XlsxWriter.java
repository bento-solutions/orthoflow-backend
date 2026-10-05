package com.orthoflow.export.infrastructure;

import com.orthoflow.export.application.dto.TableExport;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Streaming (SXSSF) so a year of payments does not sit in memory as a DOM:
 * rows past the window are flushed to disk as they are written.
 */
public final class XlsxWriter {

    private static final int WINDOW = 200;

    private XlsxWriter() {
    }

    public static byte[] write(TableExport table) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW);
        try {
            Sheet sheet = workbook.createSheet(sheetName(table.title()));
            CellStyle header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            CellStyle moneyBold = workbook.createCellStyle();
            moneyBold.cloneStyleFrom(money);
            moneyBold.setFont(bold);

            int r = 0;
            Row head = sheet.createRow(r++);
            List<TableExport.Column> columns = table.columns();
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = head.createCell(c);
                cell.setCellValue(columns.get(c).header());
                cell.setCellStyle(header);
            }
            for (List<Object> values : table.rows()) {
                fill(sheet.createRow(r++), values, money, date, null);
            }
            if (table.totals() != null) {
                fill(sheet.createRow(r), table.totals(), moneyBold, date, bold);
            }
            sheet.createFreezePane(0, 1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException ignored) {
                // Nothing to recover: the bytes are already produced or the write failed above.
            }
        }
    }

    private static void fill(Row row, List<Object> values, CellStyle money, CellStyle date, Font bold) {
        for (int c = 0; c < values.size(); c++) {
            Object value = values.get(c);
            Cell cell = row.createCell(c);
            if (value == null) {
                continue;
            }
            if (value instanceof BigDecimal d) {
                cell.setCellValue(d.doubleValue());
                cell.setCellStyle(money);
            } else if (value instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else if (value instanceof LocalDate d) {
                cell.setCellValue(java.sql.Date.valueOf(d));
                cell.setCellStyle(date);
            } else if (value instanceof OffsetDateTime d) {
                cell.setCellValue(Cells.text(d));
            } else {
                // Written as a string cell, which Excel never evaluates as a formula.
                cell.setCellValue(Cells.text(value));
            }
        }
    }

    /** Sheet names are limited to 31 characters and a few forbidden ones. */
    private static String sheetName(String title) {
        String clean = title == null || title.isBlank() ? "Export" : title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }
}
