package com.orthoflow.prescription.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.prescription.application.PrescriptionService;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.AllergyWarning;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.CheckRequest;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.IssueRequest;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.LibraryEntry;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.TemplateRequest;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.TemplateView;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.View;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Ordonnances, the clinic's templates and the reference library they come from. */
@RestController
@RequestMapping("/prescriptions")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService service;
    private final CurrentUserProvider currentUser;

    @GetMapping("/library")
    public List<LibraryEntry> library() {
        return service.library(currentUser.requirePracticeId());
    }

    @PostMapping("/library/{code}/adopt")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateView adopt(@PathVariable String code) {
        return service.adopt(currentUser.requirePracticeId(), currentUser.requireUserId(), code);
    }

    @GetMapping("/templates")
    public List<TemplateView> templates(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.templates(currentUser.requirePracticeId(), includeInactive);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateView createTemplate(@Valid @RequestBody TemplateRequest request) {
        return service.createTemplate(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PutMapping("/templates/{id}")
    public TemplateView updateTemplate(@PathVariable UUID id, @Valid @RequestBody TemplateRequest request) {
        return service.updateTemplate(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request);
    }

    @PostMapping("/templates/{id}/review")
    public TemplateView review(@PathVariable UUID id) {
        return service.review(currentUser.requirePracticeId(), currentUser.requireUserId(), id);
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@PathVariable UUID id) {
        service.deleteTemplate(currentUser.requirePracticeId(), id);
    }

    /** The allergy warnings a set of drugs raises for a patient, before issuing. */
    @PostMapping("/check")
    public List<AllergyWarning> check(@Valid @RequestBody CheckRequest request) {
        return service.check(currentUser.requirePracticeId(), request);
    }

    @GetMapping
    public List<View> list(@RequestParam UUID patientId) {
        return service.list(currentUser.requirePracticeId(), patientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View issue(@Valid @RequestBody IssueRequest request) {
        return service.issue(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        byte[] pdf = service.file(currentUser.requirePracticeId(), id);
        return ResponseEntity.ok().header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline; filename=\"ordonnance.pdf\"")
                .header("X-Content-Type-Options", "nosniff").body(pdf);
    }

    @PostMapping("/{id}/void")
    public View voidPrescription(@PathVariable UUID id) {
        return service.voidPrescription(currentUser.requirePracticeId(), id);
    }
}
