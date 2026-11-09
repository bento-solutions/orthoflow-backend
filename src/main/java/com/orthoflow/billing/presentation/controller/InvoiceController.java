package com.orthoflow.billing.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.billing.application.dto.CreateInvoiceRequest;
import com.orthoflow.billing.application.dto.InvoiceResponse;
import com.orthoflow.billing.application.service.BillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final BillingService billingService;
    private final CurrentUserProvider currentUserProvider;
    private final com.orthoflow.billing.application.service.InvoicePdfService invoicePdf;
    private final com.orthoflow.export.application.service.ExportService exportService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse createInvoice(@Valid @RequestBody CreateInvoiceRequest request) {
        return billingService.createInvoice(request, currentUserProvider.requireUserId());
    }

    @GetMapping
    public Page<InvoiceResponse> getInvoices(
            @RequestParam(required = false) UUID patientId,
            @PageableDefault(size = 200, sort = "issueDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return billingService.getInvoices(patientId, pageable);
    }

    @GetMapping("/{id}")
    public InvoiceResponse getInvoice(@PathVariable UUID id) {
        return billingService.getInvoice(id);
    }

    /** The printed invoice, with the clinic's letterhead, in {@code lang} (fr, en, ar). */
    @GetMapping("/{id}/pdf")
    public org.springframework.http.ResponseEntity<byte[]> pdf(@PathVariable UUID id, @RequestParam(defaultValue = "fr") String lang) {
        var rendered = invoicePdf.render(id, lang);
        return exportService.download(rendered.bytes(), com.orthoflow.export.application.dto.ExportFormat.PDF,
                "facture-" + (rendered.number() == null ? id : rendered.number()));
    }

    /** Body {"practitionerId": "..."}; null clears the attribution. */
    @PutMapping("/{id}/practitioner")
    public InvoiceResponse assignPractitioner(@PathVariable UUID id, @RequestBody AssignPractitioner body) {
        return billingService.assignPractitioner(id, body.practitionerId(), currentUserProvider.requireUserId());
    }

    public record AssignPractitioner(UUID practitionerId) {
    }

    @GetMapping("/summary")
    public com.orthoflow.billing.application.dto.BillingSummaryResponse getBillingSummary() {
        return billingService.getBillingSummary();
    }
}
