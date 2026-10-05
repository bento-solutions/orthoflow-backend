package com.orthoflow.billing.presentation.controller;

import com.orthoflow.billing.application.service.TaxDocumentService;
import com.orthoflow.billing.domain.model.TaxDocument;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.export.application.service.ExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Fee notes and mutual-insurance care forms: issue, track delivery, reprint as a duplicate, export the register. */
@RestController
@RequestMapping("/tax-documents")
@RequiredArgsConstructor
public class TaxDocumentController {

    private final TaxDocumentService service;
    private final ExportService exportService;
    private final CurrentUserProvider currentUser;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaxDocumentService.View issue(@Valid @RequestBody TaxDocumentService.Issue request) {
        return service.issue(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @GetMapping
    public List<TaxDocumentService.View> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TaxDocument.Kind kind, @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) TaxDocument.Status status, @RequestParam(required = false) Boolean duplicates) {
        LocalDate end = to != null ? to : LocalDate.now();
        return service.list(currentUser.requirePracticeId(), from != null ? from : end.minusMonths(3), end, kind, patientId, status, duplicates);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TaxDocument.Kind kind, @RequestParam(defaultValue = "csv") String format,
            @RequestParam(defaultValue = "fr") String lang) {
        UUID practice = currentUser.requirePracticeId();
        LocalDate end = to != null ? to : LocalDate.now();
        var rows = service.list(practice, from != null ? from : end.minusMonths(3), end, kind, null, null, null);
        return exportService.respond(service.register(rows, lang), format, "registre-documents", practice, lang);
    }

    /** The document as issued, to view or print. */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        byte[] pdf = service.file(currentUser.requirePracticeId(), id);
        return ResponseEntity.ok().header("Content-Type", "application/pdf").header("Content-Disposition", "inline; filename=\"document.pdf\"")
                .header("X-Content-Type-Options", "nosniff").body(pdf);
    }

    @PostMapping("/{id}/deliver")
    public TaxDocumentService.View deliver(@PathVariable UUID id) {
        return service.deliver(currentUser.requirePracticeId(), id);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public TaxDocumentService.View duplicate(@PathVariable UUID id, @RequestParam(defaultValue = "fr") String lang) {
        return service.duplicate(currentUser.requirePracticeId(), currentUser.requireUserId(), id, lang);
    }

    @PostMapping("/{id}/void")
    public TaxDocumentService.View voidDocument(@PathVariable UUID id) {
        return service.voidDocument(currentUser.requirePracticeId(), id);
    }
}
