package com.orthoflow.treatment.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.treatment.application.dto.TreatmentInvoiceRequest;
import com.orthoflow.treatment.application.dto.TreatmentInvoiceResponse;
import com.orthoflow.treatment.application.service.TreatmentInvoiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/stock/treatment-invoices")
@RequiredArgsConstructor
public class TreatmentInvoiceController {

    private final TreatmentInvoiceService treatmentInvoiceService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public ResponseEntity<List<TreatmentInvoiceResponse>> getAll() {
        return ResponseEntity.ok(treatmentInvoiceService.getAllInvoices().stream()
                .map(TreatmentInvoiceResponse::from).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TreatmentInvoiceResponse> getById(@PathVariable UUID id) {
        return treatmentInvoiceService.getInvoiceById(id)
                .map(ti -> ResponseEntity.ok(TreatmentInvoiceResponse.from(ti)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/patient/{patientId}")
    public ResponseEntity<List<TreatmentInvoiceResponse>> getByPatient(@PathVariable UUID patientId) {
        return ResponseEntity.ok(treatmentInvoiceService.getInvoicesByPatient(patientId).stream()
                .map(TreatmentInvoiceResponse::from).collect(Collectors.toList()));
    }

    @PostMapping("/draft")
    public ResponseEntity<TreatmentInvoiceResponse> createDraft(
            @RequestParam UUID patientId,
            @RequestParam UUID treatmentId,
            @RequestParam(required = false) String surface) {

        return ResponseEntity.ok(TreatmentInvoiceResponse.from(treatmentInvoiceService.createDraftFromTreatment(
                patientId, treatmentId, currentUserProvider.requireUserId(), surface)));
    }

    @PostMapping
    public ResponseEntity<TreatmentInvoiceResponse> save(@Valid @RequestBody TreatmentInvoiceRequest request) {
        return ResponseEntity.ok(TreatmentInvoiceResponse.from(
                treatmentInvoiceService.saveInvoice(request, currentUserProvider.requireUserId())));
    }

    /**
     * The frontend's saveTreatmentInvoice() sends PUT here (not POST) once
     * an invoice has an id — this mapping was missing entirely until now,
     * so editing an existing draft 404'd. Same upsert service method as the
     * bare POST; the path id wins over whatever the body carries, since a
     * URL path segment is a stronger signal of intent than a body field a
     * client could get out of sync.
     */
    @PutMapping("/{id}")
    public ResponseEntity<TreatmentInvoiceResponse> update(@PathVariable UUID id, @Valid @RequestBody TreatmentInvoiceRequest request) {
        request.setId(id);
        return ResponseEntity.ok(TreatmentInvoiceResponse.from(
                treatmentInvoiceService.saveInvoice(request, currentUserProvider.requireUserId())));
    }

    @PostMapping("/{id}/finalize")
    public ResponseEntity<TreatmentInvoiceResponse> finalizeInvoice(@PathVariable UUID id) {
        return ResponseEntity.ok(TreatmentInvoiceResponse.from(
                treatmentInvoiceService.finalizeInvoice(id, currentUserProvider.requireUserId())));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<TreatmentInvoiceResponse> cancelInvoice(@PathVariable UUID id) {
        return ResponseEntity.ok(TreatmentInvoiceResponse.from(
                treatmentInvoiceService.cancelInvoice(id, currentUserProvider.requireUserId())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        treatmentInvoiceService.deleteInvoice(id);
        return ResponseEntity.noContent().build();
    }
}
