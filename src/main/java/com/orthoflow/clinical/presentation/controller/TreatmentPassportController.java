package com.orthoflow.clinical.presentation.controller;

import com.orthoflow.clinical.application.dto.TreatmentPassport;
import com.orthoflow.clinical.application.service.TreatmentPassportService;
import com.orthoflow.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The patient's treatment passport. Under {@code /clinical-record}, so the
 * existing clinical-role rule covers it: the front desk cannot hand out a
 * patient's clinical history.
 */
@RestController
@RequestMapping("/patients/{patientId}/clinical-record/passport")
@RequiredArgsConstructor
public class TreatmentPassportController {

    private final TreatmentPassportService service;
    private final CurrentUserProvider currentUser;

    /** The passport for the screen. Nothing leaves the system, so nothing is logged. */
    @GetMapping
    public TreatmentPassport preview(@PathVariable UUID patientId) {
        return service.build(currentUser.requirePracticeId(), patientId);
    }

    /** The machine-readable copy, as a file another OrthoFlow clinic can import. */
    @GetMapping("/download.json")
    public ResponseEntity<TreatmentPassport> json(@PathVariable UUID patientId) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"treatment-passport.json\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(service.export(currentUser.requirePracticeId(), patientId));
    }

    /** The printed copy. */
    @GetMapping("/download.pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID patientId, @RequestParam(defaultValue = "fr") String lang) {
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"treatment-passport.pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(service.pdf(currentUser.requirePracticeId(), patientId, lang));
    }
}
