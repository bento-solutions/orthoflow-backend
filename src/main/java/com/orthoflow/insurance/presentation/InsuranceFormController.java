package com.orthoflow.insurance.presentation;

import com.orthoflow.insurance.application.InsuranceFormService;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.CreateRequest;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.LayoutView;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Preview;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.SendRequest;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.View;
import com.orthoflow.insurance.domain.model.InsuranceForm;
import com.orthoflow.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Care forms filled for the patient's insurer: which form a patient needs, making one,
 * the PDF (or the overlay for a numbered paper form), and the front desk's steps.
 */
@RestController
@RequestMapping("/insurance-forms")
@RequiredArgsConstructor
public class InsuranceFormController {

    private final InsuranceFormService service;
    private final CurrentUserProvider currentUser;

    @GetMapping("/layouts")
    public List<LayoutView> layouts() {
        return service.layouts();
    }

    @GetMapping("/preview")
    public Preview preview(@RequestParam UUID patientId, @RequestParam(required = false) UUID practitionerId) {
        return service.preview(currentUser.requirePracticeId(), currentUser.requireUserId(), patientId, practitionerId);
    }

    @GetMapping
    public List<View> list(@RequestParam(required = false) UUID patientId,
                           @RequestParam(required = false) InsuranceForm.Status status) {
        return service.list(currentUser.requirePracticeId(), patientId, status);
    }

    @GetMapping("/{id}")
    public View get(@PathVariable UUID id) {
        return service.get(currentUser.requirePracticeId(), id);
    }

    /** The filled form; with {@code overlay=true} only the added text, to print onto the patient's paper form. */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean overlay) {
        byte[] pdf = service.file(currentUser.requirePracticeId(), id, overlay);
        return ResponseEntity.ok().header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline; filename=\"" + (overlay ? "calque" : "feuille-de-soins") + ".pdf\"")
                .header("X-Content-Type-Options", "nosniff").body(pdf);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@Valid @RequestBody CreateRequest request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PostMapping("/{id}/send")
    public View send(@PathVariable UUID id, @Valid @RequestBody(required = false) SendRequest request) {
        return service.send(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request);
    }

    @PostMapping("/{id}/printed")
    public View printed(@PathVariable UUID id) {
        return service.markPrinted(currentUser.requirePracticeId(), id);
    }

    @PostMapping("/{id}/handed-over")
    public View handedOver(@PathVariable UUID id) {
        return service.markHandedOver(currentUser.requirePracticeId(), id);
    }

    @PostMapping("/{id}/refresh")
    public View refresh(@PathVariable UUID id) {
        return service.refresh(currentUser.requirePracticeId(), currentUser.requireUserId(), id);
    }

    @PostMapping("/{id}/void")
    public View voidForm(@PathVariable UUID id) {
        return service.voidForm(currentUser.requirePracticeId(), id);
    }
}
