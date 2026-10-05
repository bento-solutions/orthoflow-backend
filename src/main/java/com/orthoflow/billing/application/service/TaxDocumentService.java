package com.orthoflow.billing.application.service;

import com.orthoflow.billing.domain.model.Invoice;
import com.orthoflow.billing.domain.model.InvoiceLine;
import com.orthoflow.billing.domain.model.Payment;
import com.orthoflow.billing.domain.model.TaxDocument;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import com.orthoflow.billing.infrastructure.adapter.persistence.TaxDocumentJpaRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.dto.TableExport.Column;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.Cells;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.patient.application.port.PatientIdentity;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Fee notes and care forms. Each is rendered once, stored as issued and numbered
 * from a sequence, so the copy the patient holds and the copy the clinic keeps are
 * the same bytes. A second print is a duplicate with its own number, marked as
 * such, never a quiet re-render of the original.
 */
@Service
@RequiredArgsConstructor
public class TaxDocumentService {

    public record Issue(@NotNull TaxDocument.Kind kind, @NotNull UUID invoiceId, UUID practitionerId, String lang, String notes) {
    }

    public record View(UUID id, TaxDocument.Kind kind, String number, UUID patientId, String patientName, UUID invoiceId,
                       UUID practitionerId, BigDecimal amount, OffsetDateTime issuedAt, OffsetDateTime deliveredAt,
                       TaxDocument.Status status, boolean duplicate, UUID duplicateOf, UUID fileId, String notes) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TaxDocumentJpaRepository documents;
    private final InvoiceRepository invoices;
    private final PatientLookup patientLookup;
    private final PractitionerService practitionerService;
    private final PdfService pdfService;
    private final LetterheadProvider letterheadProvider;
    private final FileService fileService;
    private final JdbcTemplate jdbc;
    private final PracticeZone practiceZone;

    @Transactional
    public View issue(UUID practiceId, UUID actorId, Issue r) {
        Invoice invoice = invoices.findById(r.invoiceId()).filter(i -> practiceId.equals(i.getPracticeId()))
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (documents.existsByInvoiceIdAndKindAndDuplicateOfIsNullAndStatusNot(invoice.getId(), r.kind(), TaxDocument.Status.VOID)) {
            throw new ConflictException("A " + r.kind() + " was already issued for this invoice; print a duplicate instead");
        }
        UUID practitionerId = r.practitionerId() != null ? r.practitionerId() : invoice.getPractitionerId();
        TaxDocument doc = TaxDocument.builder().id(UUID.randomUUID()).practiceId(practiceId).kind(r.kind()).number(nextNumber(r.kind()))
                .patientId(invoice.getPatientId()).invoiceId(invoice.getId()).practitionerId(practitionerId)
                .amount(invoice.getTotal()).notes(r.notes()).createdBy(actorId).build();
        return render(doc, invoice, language(r.lang(), practiceId), false, actorId);
    }

    /** A reprint: new number ("…/D1"), flagged DUPLICATA on the page and in the register. */
    @Transactional
    public View duplicate(UUID practiceId, UUID actorId, UUID id, String lang) {
        TaxDocument original = require(practiceId, id);
        if (original.getStatus() == TaxDocument.Status.VOID) {
            throw new ConflictException("A voided document cannot be duplicated");
        }
        if (original.getDuplicateOf() != null) {
            throw new ConflictException("Duplicate the original, not a duplicate");
        }
        Invoice invoice = invoices.findById(original.getInvoiceId()).orElseThrow(() -> new NotFoundException("Invoice not found"));
        TaxDocument copy = TaxDocument.builder().id(UUID.randomUUID()).practiceId(practiceId).kind(original.getKind())
                .number(original.getNumber() + "/D" + (documents.countByDuplicateOf(original.getId()) + 1))
                .patientId(original.getPatientId()).invoiceId(original.getInvoiceId()).practitionerId(original.getPractitionerId())
                .amount(original.getAmount()).duplicateOf(original.getId()).createdBy(actorId).build();
        return render(copy, invoice, language(lang, practiceId), true, actorId);
    }

    @Transactional
    public View deliver(UUID practiceId, UUID id) {
        TaxDocument d = require(practiceId, id);
        if (d.getStatus() == TaxDocument.Status.VOID) {
            throw new ConflictException("A voided document cannot be delivered");
        }
        d.setStatus(TaxDocument.Status.DELIVERED);
        d.setDeliveredAt(OffsetDateTime.now());
        return view(d, name(d.getPatientId()));
    }

    @Transactional
    public View voidDocument(UUID practiceId, UUID id) {
        TaxDocument d = require(practiceId, id);
        d.setStatus(TaxDocument.Status.VOID);
        return view(d, name(d.getPatientId()));
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID practiceId, LocalDate from, LocalDate to, TaxDocument.Kind kind, UUID patientId,
                           TaxDocument.Status status, Boolean duplicates) {
        ZoneId zone = practiceZone.of(practiceId);
        List<TaxDocument> rows = documents.search(practiceId, from.atStartOfDay(zone).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), kind, patientId, status,
                duplicates == null ? "ANY" : duplicates ? "ONLY" : "NONE");
        Map<UUID, String> names = new HashMap<>();
        return rows.stream().map(d -> view(d, names.computeIfAbsent(d.getPatientId(), this::name))).toList();
    }

    @Transactional(readOnly = true)
    public byte[] file(UUID practiceId, UUID id) {
        TaxDocument d = require(practiceId, id);
        if (d.getFileId() == null) {
            throw new NotFoundException("This document has no file");
        }
        return fileService.read(fileService.require(practiceId, d.getFileId()));
    }

    public TableExport register(List<View> rows, String lang) {
        boolean en = "en".equals(lang);
        return new TableExport(en ? "Tax documents register" : "Registre des documents", null,
                List.of(Column.text(en ? "Number" : "Numéro"), Column.text("Type"), Column.text("Patient"), Column.text("Date"),
                        Column.number(en ? "Amount" : "Montant"), Column.text(en ? "Status" : "Statut"), Column.text(en ? "Duplicate" : "Duplicata")),
                rows.stream().map(v -> List.<Object>of(v.number(), v.kind().name(), nz(v.patientName()), v.issuedAt(), v.amount(),
                        v.status().name(), v.duplicate())).toList());
    }

    private View render(TaxDocument doc, Invoice invoice, String lang, boolean duplicate, UUID actorId) {
        PatientIdentity patient = patientLookup.findIdentity(doc.getPatientId()).orElseThrow(() -> new NotFoundException("Patient not found"));
        Letterhead letterhead = letterheadProvider.forPractice(doc.getPracticeId());
        BigDecimal paid = invoice.getPayments().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", letterhead);
        model.put("labels", TaxDocumentLabels.of(lang, doc.getKind()));
        model.put("doc", Map.of("number", doc.getNumber(), "duplicate", duplicate,
                "issuedOn", DAY.format(OffsetDateTime.now().atZoneSameInstant(practiceZone.of(doc.getPracticeId())))));
        model.put("patient", patient);
        Practitioner practitioner = doc.getPractitionerId() == null ? null : practitionerService.require(doc.getPracticeId(), doc.getPractitionerId());
        model.put("practitioner", practitioner == null ? null : Map.of("name", practitioner.getDisplayName(),
                "inpe", practitioner.getInpe() == null ? "" : practitioner.getInpe()));
        model.put("invoice", invoiceModel(invoice, paid));
        model.put("words", "fr".equals(lang) ? AmountInWords.french(invoice.getTotal(), "dirham", "centime") : null);
        byte[] pdf = pdfService.render(doc.getKind() == TaxDocument.Kind.FEE_NOTE ? "fee-note" : "care-form", model, lang);
        var stored = fileService.storeGenerated(doc.getPracticeId(), FileOwnerType.TAX_DOCUMENT, doc.getId(),
                doc.getNumber().replace('/', '-') + ".pdf", "application/pdf", pdf, actorId);
        doc.setFileId(stored.getId());
        return view(documents.save(doc), patient.fullName());
    }

    private Map<String, Object> invoiceModel(Invoice invoice, BigDecimal paid) {
        BigDecimal discount = nz(invoice.getDiscountAmount());
        BigDecimal tax = nz(invoice.getTaxAmount());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (InvoiceLine l : invoice.getLines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", l.getLabel());
            m.put("actCode", l.getActCode());
            m.put("coefficient", null);
            m.put("quantity", l.getQuantity().stripTrailingZeros().toPlainString());
            m.put("unitPrice", Cells.money(l.getUnitPrice()));
            m.put("discount", l.getDiscountPct() == null || l.getDiscountPct().signum() == 0 ? "–" : l.getDiscountPct().stripTrailingZeros().toPlainString() + " %");
            m.put("total", Cells.money(l.getLineTotal()));
            lines.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", invoice.getInvoiceNumber());
        m.put("currency", invoice.getCurrency());
        m.put("lines", lines);
        m.put("subtotal", Cells.money(nz(invoice.getSubtotal())));
        m.put("discount", Cells.money(discount));
        m.put("hasDiscount", discount.signum() > 0);
        m.put("tax", Cells.money(tax));
        m.put("hasTax", tax.signum() > 0);
        m.put("total", Cells.money(invoice.getTotal()));
        m.put("paid", Cells.money(paid));
        m.put("balance", Cells.money(invoice.getTotal().subtract(paid)));
        return m;
    }

    private String nextNumber(TaxDocument.Kind kind) {
        Long n = jdbc.queryForObject("SELECT nextval('tax_document_seq')", Long.class);
        return (kind == TaxDocument.Kind.FEE_NOTE ? "FN-" : "FS-") + LocalDate.now().getYear() + "-" + String.format("%05d", n);
    }

    private String language(String requested, UUID practiceId) {
        if (requested != null && List.of("fr", "en", "ar").contains(requested)) {
            return requested;
        }
        return "fr";
    }

    private TaxDocument require(UUID practiceId, UUID id) {
        return documents.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Document not found"));
    }

    private String name(UUID patientId) {
        return patientLookup.findSummary(patientId).map(com.orthoflow.patient.application.port.PatientSummary::fullName).orElse(null);
    }

    private View view(TaxDocument d, String patientName) {
        return new View(d.getId(), d.getKind(), d.getNumber(), d.getPatientId(), patientName, d.getInvoiceId(), d.getPractitionerId(),
                d.getAmount(), d.getIssuedAt(), d.getDeliveredAt(), d.getStatus(), d.getDuplicateOf() != null, d.getDuplicateOf(),
                d.getFileId(), d.getNotes());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
