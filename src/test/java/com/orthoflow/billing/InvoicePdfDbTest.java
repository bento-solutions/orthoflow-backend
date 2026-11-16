package com.orthoflow.billing;

import com.orthoflow.billing.application.service.InvoicePdfService;
import com.orthoflow.common.exception.IncompleteProfileException;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            assertThat(text).contains("FACTURE").contains(number).contains("Yasmine Alaoui").contains("Obturation composite")
                    .contains("1\u00a0500,00");
            assertThat(document.getDocumentCatalog().getStructureTreeRoot()).isNotNull();
        }
    }

    @Test
    void everyPageCarriesTheClinicHeaderAndTheLegalFooter() throws Exception {
        JdbcTemplate jdbc = PostgresTestSupport.jdbc();
        UUID practice = PostgresTestSupport.newPractice(jdbc);
        jdbc.update("UPDATE practices SET legal_name = 'Cabinet Test SARL', rc = '445566', patente = '778899', rib = '011 780 0000 1234' WHERE id = ?", practice);
        UUID patient = PostgresTestSupport.patient(jdbc, practice, "Omar", "Bennis", "1990-01-01", "0622222222", null);
        UUID admin = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)",
                admin, admin + "@x.ma", practice);
        UUID invoice = UUID.randomUUID();
        String number = "INV-" + invoice.toString().substring(0, 8);
        jdbc.update("INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, currency, subtotal, tax_amount, total, region_code, issue_date, created_by) "
                + "VALUES (?, ?, ?, ?, 'SENT', 'MAD', 6000, 0, 6000, 'MA', current_date, ?)", invoice, practice, patient, number, admin);
        for (int i = 0; i < 60; i++) {
            jdbc.update("INSERT INTO invoice_lines (id, invoice_id, act_code, label, quantity, unit_price, discount_pct, line_total, sort_order, practice_id) "
                    + "VALUES (?, ?, 'D', ?, 1, 100, 0, 100, ?, ?)", UUID.randomUUID(), invoice, "Séance " + (i + 1), i, practice);
        }
        signInAs(admin, practice);

        var rendered = invoicePdf.render(invoice, "fr");

        try (var document = org.apache.pdfbox.Loader.loadPDF(rendered.bytes())) {
            int pages = document.getNumberOfPages();
            assertThat(pages).isGreaterThan(1);
            for (int page = 1; page <= pages; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                assertThat(text).as("page %d", page)
                        .contains("12 rue des Tests, Casablanca").contains(number)
                        .contains("Cabinet Test SARL").contains("ICE 001234567000089").contains("IF 12345678")
                        .contains("RC 445566").contains("Patente 778899").contains("RIB 011 780 0000 1234")
                        .contains(page + " / " + pages);
            }
        }
    }

    @Test
    void anInvoiceIsRefusedWhileTheClinicProfileLacksItsTaxNumbers() {
        JdbcTemplate jdbc = PostgresTestSupport.jdbc();
        UUID practice = PostgresTestSupport.newPractice(jdbc);
        jdbc.update("UPDATE practices SET ice = NULL, address = '  ' WHERE id = ?", practice);
        UUID patient = PostgresTestSupport.patient(jdbc, practice, "Nadia", "Fassi", "1990-01-01", "0633333333", null);
        UUID admin = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)",
                admin, admin + "@x.ma", practice);
        UUID invoice = UUID.randomUUID();
        jdbc.update("INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, currency, subtotal, tax_amount, total, region_code, issue_date, created_by) "
                + "VALUES (?, ?, ?, ?, 'SENT', 'MAD', 100, 0, 100, 'MA', current_date, ?)", invoice, practice, patient, "INV-" + invoice.toString().substring(0, 8), admin);
        signInAs(admin, practice);

        assertThatThrownBy(() -> invoicePdf.render(invoice, "fr"))
                .isInstanceOfSatisfying(IncompleteProfileException.class,
                        e -> assertThat(e.missing()).containsExactly("ice", "address"));
    }
}
