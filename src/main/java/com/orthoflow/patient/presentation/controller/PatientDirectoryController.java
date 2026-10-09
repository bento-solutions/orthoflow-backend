package com.orthoflow.patient.presentation.controller;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.patient.application.dto.PatientDirectoryDtos.*;
import com.orthoflow.patient.application.service.PatientDirectoryService;
import com.orthoflow.patient.application.service.PatientMergeService;
import com.orthoflow.patient.application.service.PatientService;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.infrastructure.adapter.query.PatientDirectoryQuery;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The patient list as the reception screen uses it (paged, sortable, filtered),
 * its figures, the duplicate check, photos, and the lists the form draws from.
 * The plain {@code GET /patients} stays as it was for every screen that still
 * reads it.
 */
@RestController
@RequiredArgsConstructor
public class PatientDirectoryController {

    private final PatientDirectoryService directory;
    private final PatientService patientService;
    private final PatientMergeService mergeService;
    private final FileService fileService;
    private final CurrentUserProvider currentUser;

    @GetMapping("/patients/list")
    public Page<Row> list(@RequestParam(required = false) String search,
                          @RequestParam(required = false) String gender,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) UUID practitionerId,
                          @RequestParam(required = false) UUID insurerId,
                          @RequestParam(defaultValue = "false") boolean duplicatesOnly,
                          @RequestParam(defaultValue = "false") boolean debtOnly,
                          @RequestParam(defaultValue = "false") boolean landingPageOnly,
                          @RequestParam(defaultValue = "name") String sort,
                          @RequestParam(defaultValue = "asc") String dir,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "25") int size) {
        var filter = new PatientDirectoryQuery.Filter(currentUser.requirePracticeId(), search,
                gender == null || gender.isBlank() ? null : gender.toUpperCase(), blankToNull(status),
                practitionerId, insurerId, duplicatesOnly, debtOnly, landingPageOnly);
        return directory.list(filter, sort, "desc".equalsIgnoreCase(dir), page, size);
    }

    @GetMapping("/patients/kpis")
    public Kpis kpis() {
        return directory.kpis(currentUser.requirePracticeId());
    }

    @GetMapping("/patients/duplicates")
    public List<DuplicatePair> duplicates() {
        return directory.duplicates(currentUser.requirePracticeId());
    }

    @PostMapping(value = "/patients/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, UUID> uploadPhoto(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        Patient patient = patientService.getPatientById(id);
        StoredFile stored = fileService.store(currentUser.requirePracticeId(), FileOwnerType.PATIENT_PHOTO, id, file,
                currentUser.requireUserId());
        patientService.setPhoto(patient.getId(), stored.getId());
        return Map.of("photoFileId", stored.getId());
    }

    @GetMapping("/patients/{id}/photo")
    public ResponseEntity<InputStreamResource> photo(@PathVariable UUID id) {
        Patient patient = patientService.getPatientById(id);
        if (patient.getPhotoFileId() == null) {
            throw new NotFoundException("This patient has no photo");
        }
        StoredFile file = fileService.require(currentUser.requirePracticeId(), patient.getPhotoFileId());
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=3600")
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .body(new InputStreamResource(fileService.open(file)));
    }

    // ── Merge ──
    @GetMapping("/patients/{targetId}/merge-preview")
    public PatientMergeService.Preview mergePreview(@PathVariable UUID targetId, @RequestParam UUID sourceId) {
        return mergeService.preview(currentUser.requirePracticeId(), targetId, sourceId);
    }

    /** Merges {@code sourceId} into the patient in the path, who stays. Irreversible: the duplicate is archived. */
    @PostMapping("/patients/{targetId}/merge")
    public PatientMergeService.Result merge(@PathVariable UUID targetId, @Valid @RequestBody PatientMergeService.Request request) {
        return mergeService.merge(currentUser.requirePracticeId(), currentUser.requireUserId(), targetId, request);
    }

    // ── Reference lists ──
    @GetMapping("/reference/insurers")
    public List<InsurerResponse> insurers(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return directory.insurers(currentUser.requirePracticeId(), includeInactive);
    }

    @PostMapping("/reference/insurers")
    @ResponseStatus(HttpStatus.CREATED)
    public InsurerResponse createInsurer(@Valid @RequestBody InsurerRequest request) {
        return directory.createInsurer(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/reference/insurers/{id}")
    public InsurerResponse updateInsurer(@PathVariable UUID id, @Valid @RequestBody InsurerRequest request) {
        return directory.updateInsurer(currentUser.requirePracticeId(), id, request);
    }

    @GetMapping("/reference/referral-sources")
    public List<ReferralSourceResponse> referralSources(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return directory.referralSources(currentUser.requirePracticeId(), includeInactive);
    }

    @PostMapping("/reference/referral-sources")
    @ResponseStatus(HttpStatus.CREATED)
    public ReferralSourceResponse createReferralSource(@Valid @RequestBody ReferralSourceRequest request) {
        return directory.createReferralSource(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/reference/referral-sources/{id}")
    public ReferralSourceResponse updateReferralSource(@PathVariable UUID id, @Valid @RequestBody ReferralSourceRequest request) {
        return directory.updateReferralSource(currentUser.requirePracticeId(), id, request);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
