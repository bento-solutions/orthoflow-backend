package com.orthoflow.compliance.presentation.controller;

import com.orthoflow.audit.application.service.AuditCategory;
import com.orthoflow.audit.application.service.AuditLog;
import com.orthoflow.patient.application.service.PatientService;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.compliance.application.dto.PatientDataExport;
import com.orthoflow.compliance.application.service.DataExportService;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The engineering half of GDPR/Law 09-08 data-subject rights (audit V.8) —
 * export (Art. 15/20) and consent capture. Restricted to ADMIN: this
 * returns a patient's complete cross-module record in one response, which
 * is exactly the kind of bulk-access operation that should require the
 * same elevated trust as the hard-erasure path
 * ({@code PatientController.erasePatient}), not be reachable by any
 * authenticated staff account.
 *
 * What this endpoint does NOT cover: retention-period enforcement (no
 * statutory retention period is encoded here — that's a legal
 * determination for the operating clinic, not something to hardcode into
 * this codebase), a DPA/processor register, or CNDP notification filings.
 * Those are administrative/legal actions outside what a codebase can
 * discharge on its own — see AUDIT.md P3 item #43 and
 * docs/RUNBOOK.md.
 */
@RestController
@RequestMapping("/patients/{patientId}/compliance")
@RequiredArgsConstructor
public class DataExportController {

    private final DataExportService dataExportService;
    private final PatientService patientService;
    private final AuditLog audit;

    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public PatientDataExport exportData(@PathVariable UUID patientId) {
        PatientDataExport export = dataExportService.exportPatientData(patientId);
        audit.record(AuditCategory.DATA_RIGHTS, "PATIENT_EXPORTED", patientId,
                java.util.Map.of("tables", export.getRecords().size(), "files", export.getFiles().size()));
        return export;
    }

    /**
     * Erasure for a patient whose financial records the law makes the clinic keep: everything clinical and
     * personal is removed, the financial records stay without a name, the patient becomes an archived shell.
     * Irreversible. For a patient with no such records, erase them ({@code DELETE /patients/{id}/erase}).
     */
    @PostMapping("/anonymise")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void anonymise(@PathVariable UUID patientId) {
        patientService.anonymisePatient(patientId);
        audit.record(AuditCategory.DATA_RIGHTS, "PATIENT_ANONYMISED", patientId, java.util.Map.of());
    }

    public record ConsentRequest(@Size(max = 2000) String notes) {}

    @PostMapping("/consent")
    @PreAuthorize("hasRole('ADMIN')")
    public Patient recordConsent(@PathVariable UUID patientId, @RequestBody(required = false) ConsentRequest request) {
        return dataExportService.recordConsent(patientId, request != null ? request.notes() : null);
    }
}
