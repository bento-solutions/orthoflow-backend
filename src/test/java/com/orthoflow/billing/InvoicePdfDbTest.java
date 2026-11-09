package com.orthoflow.billing;

import com.orthoflow.billing.application.service.InvoicePdfService;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The printed invoice comes from the server, names the patient whoever they are, and is tagged. */
class InvoicePdfDbTest extends SpringDbTest {

    @Autowired
    private InvoicePdfService invoicePdf;

    @Test
    void theInvoiceNamesThePatientListsItsLinesAndIsTaggedForScreenReaders() throws Exception {
        JdbcTemplate jdbc = PostgresTestSupport.jdbc();
        UUID practice = PostgresTestSupport.newPractice(jdbc);
        UUID patient = PostgresTestSupport.patient(jdbc, practice, "Yasmine", "Alaoui", "1995-05-05", "0611111111", null);
        UUID admin = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)",
                admin, admin + "@x.ma", practice);
        UUID invoice = UUID.randomUUID();
        String number = "INV-" + invoice.toString().substring(0, 8);
        jdbc.update("INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, currency, subtotal, tax_amount, total, region_code, issue_date, created_by) "
                + "VALUES (?, ?, ?, ?, 'SENT', 'MAD', 1500, 0, 1500, 'MA', current_date, ?)", invoice, practice, patient, number, admin);
        jdbc.update("INSERT INTO invoice_lines (id, invoice_id, act_code, label, quantity, unit_price, discount_pct, line_total, sort_order, practice_id) "
                + "VALUES (?, ?, 'D700', 'Obturation composite', 1, 1500, 0, 1500, 0, ?)", UUID.randomUUID(), invoice, practice);
        signInAs(admin, practice);

        var rendered = invoicePdf.render(invoice, "fr");

        assertThat(rendered.number()).isEqualTo(number);
        try (var document = org.apache.pdfbox.Loader.loadPDF(rendered.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("FACTURE").contains(number).contains("Yasmine Alaoui").contains("Obturation composite").contains("1500.00");
            assertThat(document.getDocumentCatalog().getStructureTreeRoot()).isNotNull();
        }
    }
}
