package com.orthoflow.billing.application.service;

import com.orthoflow.billing.domain.model.Invoice;
import com.orthoflow.billing.domain.model.InvoiceLine;
import com.orthoflow.billing.domain.model.Payment;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.patient.application.port.PatientLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The printed invoice, rendered here rather than in the browser (audit P1, P4). The browser drew it
 * with jsPDF from what it had loaded, which named the patient only when they were among the first 200
 * patients ("Patient: —" otherwise), had no letterhead, was not readable by a screen reader, and cost
 * the app jsPDF, html2canvas and canvg (about 750 kB) and DOMPurify's advisories.
 */
@Service
@RequiredArgsConstructor
public class InvoicePdfService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final InvoiceRepository invoices;
    private final PatientLookup patients;
    private final LetterheadProvider letterheads;
    private final PdfService pdf;

    public record Rendered(String number, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public Rendered render(java.util.UUID invoiceId, String requestedLang) {
        String lang = List.of("fr", "en", "ar").contains(requestedLang) ? requestedLang : "fr";
        Invoice invoice = invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("Invoice not found"));
        String name = patients.displayNames(invoice.getPracticeId(), List.of(invoice.getPatientId())).get(invoice.getPatientId());
        String code = patients.findIdentity(invoice.getPatientId()).map(i -> i.code()).orElse(null);

        BigDecimal paid = invoice.getPayments().stream().map(Payment::getAmount).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = nz(invoice.getTotal());
        List<Map<String, Object>> lines = new ArrayList<>();
        invoice.getLines().stream().sorted(Comparator.comparing(l -> l.getSortOrder() == null ? 0 : l.getSortOrder()))
                .forEach(line -> lines.add(line(line)));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("number", invoice.getInvoiceNumber());
        view.put("patient", name == null || name.isBlank() ? "—" : name);
        view.put("patientCode", code);
        view.put("issued", day(invoice.getIssueDate()));
        view.put("due", invoice.getDueDate() == null ? null : day(invoice.getDueDate()));
        view.put("status", label(lang, "status." + invoice.getStatus().name()));
        view.put("currency", invoice.getCurrency() == null ? "MAD" : invoice.getCurrency());
        view.put("subtotal", money(invoice.getSubtotal()));
        view.put("discount", nz(invoice.getDiscountAmount()).signum() == 0 ? null : money(invoice.getDiscountAmount()));
        view.put("tax", money(invoice.getTaxAmount()));
        view.put("total", money(total));
        view.put("paid", money(paid));
        view.put("balance", money(total.subtract(paid).max(BigDecimal.ZERO)));
        view.put("lines", lines);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", letterheads.forPractice(invoice.getPracticeId()));
        model.put("labels", labels(lang));
        model.put("invoice", view);
        return new Rendered(invoice.getInvoiceNumber(), pdf.render("invoice", model, lang));
    }

    private static Map<String, Object> line(InvoiceLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", line.getActCode() == null ? "" : line.getActCode());
        m.put("label", line.getLabel() == null ? "—" : line.getLabel());
        m.put("qty", line.getQuantity() == null ? "" : line.getQuantity().stripTrailingZeros().toPlainString());
        m.put("unit", money(line.getUnitPrice()));
        m.put("discount", line.getDiscountPct() == null || line.getDiscountPct().signum() == 0 ? ""
                : line.getDiscountPct().stripTrailingZeros().toPlainString() + " %");
        m.put("total", money(line.getLineTotal()));
        return m;
    }

    private static final Map<String, String[]> LABELS = Map.ofEntries(
            Map.entry("title", new String[] {"FACTURE", "INVOICE", "فاتورة"}),
            Map.entry("patient", new String[] {"Patient", "Patient", "المريض"}),
            Map.entry("file", new String[] {"Dossier", "File", "الملف"}),
            Map.entry("issued", new String[] {"Date", "Date", "التاريخ"}),
            Map.entry("due", new String[] {"Échéance", "Due", "تاريخ الاستحقاق"}),
            Map.entry("status", new String[] {"Statut", "Status", "الحالة"}),
            Map.entry("code", new String[] {"Code", "Code", "الرمز"}),
            Map.entry("description", new String[] {"Désignation", "Description", "البيان"}),
            Map.entry("qty", new String[] {"Qté", "Qty", "الكمية"}),
            Map.entry("unit", new String[] {"P.U.", "Unit price", "سعر الوحدة"}),
            Map.entry("discount", new String[] {"Remise", "Discount", "الخصم"}),
            Map.entry("total", new String[] {"Total", "Total", "المجموع"}),
            Map.entry("subtotal", new String[] {"Sous-total", "Subtotal", "المجموع الفرعي"}),
            Map.entry("tax", new String[] {"TVA", "Tax", "الضريبة"}),
            Map.entry("paid", new String[] {"Réglé", "Paid", "المدفوع"}),
            Map.entry("balance", new String[] {"Reste dû", "Balance due", "الباقي"}),
            Map.entry("generated", new String[] {"Document généré le", "Generated on", "أُنشئت في"}),
            Map.entry("status.DRAFT", new String[] {"Brouillon", "Draft", "مسودة"}),
            Map.entry("status.SENT", new String[] {"Émise", "Issued", "صادرة"}),
            Map.entry("status.PARTIALLY_PAID", new String[] {"Partiellement réglée", "Partly paid", "مدفوعة جزئياً"}),
            Map.entry("status.PAID", new String[] {"Réglée", "Paid", "مدفوعة"}),
            Map.entry("status.CANCELLED", new String[] {"Annulée", "Cancelled", "ملغاة"}));

    private static String label(String lang, String key) {
        String[] texts = LABELS.get(key);
        return texts == null ? key : texts["en".equals(lang) ? 1 : "ar".equals(lang) ? 2 : 0];
    }

    private static Map<String, String> labels(String lang) {
        Map<String, String> out = new LinkedHashMap<>();
        LABELS.keySet().stream().filter(k -> !k.startsWith("status.")).forEach(k -> out.put(k, label(lang, k)));
        return out;
    }

    private static String day(LocalDate date) {
        return date == null ? "—" : DAY.format(date);
    }

    private static String money(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
