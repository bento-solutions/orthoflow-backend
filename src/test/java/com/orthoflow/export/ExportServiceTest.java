package com.orthoflow.export;

import com.orthoflow.export.application.dto.ExportFormat;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.service.ExportService;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.export.infrastructure.PdfTemplateConfig;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExportServiceTest {

    private ExportService exports;

    private final TableExport table = new TableExport("Encaissements du jour", "05/10/2026",
            List.of(TableExport.Column.text("Patient"), TableExport.Column.text("Mode"), TableExport.Column.number("Montant"),
                    TableExport.Column.text("Date")),
            List.of(List.of("Sara Benziane", "Espèces", new BigDecimal("1500.5"), LocalDate.of(2026, 10, 5)),
                    List.of("=HYPERLINK(\"http://evil\")", "Chèque", new BigDecimal("200"), LocalDate.of(2026, 10, 5))),
            List.of("Total", "", new BigDecimal("1700.5"), ""));

    @BeforeEach
    void setUp() {
        PdfService pdf = new PdfService(new PdfTemplateConfig().pdfTemplateEngine());
        exports = new ExportService(pdf, practiceId -> new Letterhead("Cabinet Tazi", "SARL Tazi", "001234567000089",
                "1234567", "98765", null, "12 rue Moulay Youssef", "Casablanca", "+212 5 22 00 00 00", null, null));
    }

    @Test
    void csvStartsWithAByteOrderMarkSoExcelReadsFrenchAccents() {
        byte[] csv = exports.render(table, ExportFormat.CSV, UUID.randomUUID(), "fr");

        assertThat(csv).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertThat(text).contains("Espèces").contains("Chèque");
        assertThat(text.lines().findFirst()).contains("Patient;Mode;Montant;Date");
    }

    @Test
    void csvFormatsMoneyTheFrenchWayAndDefusesFormulas() {
        String text = new String(exports.render(table, ExportFormat.CSV, UUID.randomUUID(), "fr"), StandardCharsets.UTF_8);

        assertThat(text).contains("1 500,50").contains("05/10/2026");
        assertThat(text).contains("'=HYPERLINK").doesNotContain(";=HYPERLINK").doesNotContain("\n=HYPERLINK");
    }

    @Test
    void csvLeavesPhoneNumbersAndNegativeAmountsAloneButDefusesRealFormulas() {
        TableExport phones = new TableExport("t", null, List.of(TableExport.Column.text("a")),
                List.of(List.of("+212 661-123456"), List.of("-1 500,50"), List.of("+cmd|'/c calc'!A0"), List.of("@SUM(1)"), List.of("-2+3")));

        String text = new String(exports.render(phones, ExportFormat.CSV, UUID.randomUUID(), "fr"), StandardCharsets.UTF_8);

        assertThat(text).contains("\n+212 661-123456\r\n").contains("\n-1 500,50\r\n");
        assertThat(text).contains("'+cmd|").contains("'@SUM(1)").contains("'-2+3");
    }

    @Test
    void xlsxKeepsNumbersNumericAndStoresTextAsText() throws Exception {
        byte[] xlsx = exports.render(table, ExportFormat.XLSX, UUID.randomUUID(), "fr");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Patient");
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(1500.5);
            // A string cell is never evaluated, so the text is kept exactly as typed.
            assertThat(sheet.getRow(2).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Total");
        }
    }

    @Test
    void pdfIsRenderedWithTheClinicLetterheadAndFrenchAccents() throws Exception {
        byte[] pdf = exports.render(table, ExportFormat.PDF, UUID.randomUUID(), "fr");

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (var document = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Cabinet Tazi").contains("ICE 001234567000089").contains("Espèces").contains("Encaissements du jour");
        }
    }

    @Test
    void pdfRendersArabicWithoutFailing() throws Exception {
        TableExport arabic = new TableExport("تقرير المدفوعات", null, List.of(TableExport.Column.text("المريض")),
                List.of(List.of("سارة بنزيان")));

        byte[] pdf = exports.render(arabic, ExportFormat.PDF, UUID.randomUUID(), "ar");

        assertThat(pdf.length).isGreaterThan(1000);
        try (var document = PDDocument.load(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void anUnknownFormatIsABadRequest() {
        org.junit.jupiter.api.Assertions.assertThrows(com.orthoflow.common.exception.ValidationException.class,
                () -> ExportFormat.parse("docx"));
        assertThat(ExportFormat.parse(null)).isEqualTo(ExportFormat.PDF);
        assertThat(ExportFormat.parse("XLSX")).isEqualTo(ExportFormat.XLSX);
    }
}
