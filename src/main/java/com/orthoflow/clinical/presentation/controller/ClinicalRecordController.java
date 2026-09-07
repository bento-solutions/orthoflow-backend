package com.orthoflow.clinical.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.clinical.application.dto.*;
import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.clinical.domain.model.FindingStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The structured clinical record behind the dossier — the surface both the
 * mouse and the voice pipeline write through.
 *
 * <p>Everything here is a clinical write, so it is DOCTOR/ADMIN only:
 * an assistant can read a dossier but cannot record a finding, and a voice
 * command inherits exactly that restriction from the JWT it is issued under
 * rather than having a permission model of its own.
 */
@RestController
@RequestMapping("/patients/{patientId}/clinical-record")
@RequiredArgsConstructor
public class ClinicalRecordController {

    private final ClinicalRecordService clinicalRecordService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public PatientClinicalRecordResponse getRecord(@PathVariable UUID patientId) {
        return clinicalRecordService.getRecord(patientId);
    }

    // ── Tooth findings ──────────────────────────────────────────────────

    @GetMapping("/findings")
    public List<ToothFindingResponse> listFindings(@PathVariable UUID patientId) {
        return clinicalRecordService.listFindings(patientId);
    }

    @GetMapping("/teeth/{fdi}/findings")
    public List<ToothFindingResponse> listToothFindings(@PathVariable UUID patientId, @PathVariable String fdi) {
        return clinicalRecordService.listFindingsForTooth(patientId, fdi);
    }

    @PostMapping("/teeth/{fdi}/findings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ToothFindingResponse addFinding(
            @PathVariable UUID patientId,
            @PathVariable String fdi,
            @Valid @RequestBody AddToothFindingRequest request) {
        return clinicalRecordService.addFinding(patientId, fdi, request, currentUserProvider.requireUserId());
    }

    /**
     * RETRACTED for "the system misheard me", RESOLVED for "this has been
     * treated". Neither removes the row — see {@link FindingStatus}.
     */
    @PatchMapping("/findings/{findingId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ToothFindingResponse changeFindingStatus(
            @PathVariable UUID patientId,
            @PathVariable UUID findingId,
            @RequestParam FindingStatus status) {
        return clinicalRecordService.changeFindingStatus(patientId, findingId, status, currentUserProvider.requireUserId());
    }

    // ── Notes ───────────────────────────────────────────────────────────

    @GetMapping("/notes")
    public List<ClinicalNoteResponse> listNotes(@PathVariable UUID patientId) {
        return clinicalRecordService.listNotes(patientId);
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ClinicalNoteResponse addNote(
            @PathVariable UUID patientId,
            @Valid @RequestBody CreateClinicalNoteRequest request) {
        return clinicalRecordService.addNote(patientId, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public void deleteNote(@PathVariable UUID patientId, @PathVariable UUID noteId) {
        clinicalRecordService.deleteNote(patientId, noteId);
    }

    // ── Allergies ───────────────────────────────────────────────────────

    @GetMapping("/allergies")
    public List<AllergyResponse> listAllergies(@PathVariable UUID patientId) {
        return clinicalRecordService.listAllergies(patientId);
    }

    @PostMapping("/allergies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public AllergyResponse addAllergy(
            @PathVariable UUID patientId,
            @Valid @RequestBody AddAllergyRequest request) {
        return clinicalRecordService.addAllergy(patientId, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/allergies/{allergyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public void deleteAllergy(@PathVariable UUID patientId, @PathVariable UUID allergyId) {
        clinicalRecordService.deleteAllergy(patientId, allergyId);
    }

    // ── Medical history ─────────────────────────────────────────────────

    @GetMapping("/medical-history")
    public List<MedicalHistoryResponse> listMedicalHistory(@PathVariable UUID patientId) {
        return clinicalRecordService.listMedicalHistory(patientId);
    }

    @PostMapping("/medical-history")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public MedicalHistoryResponse addMedicalHistory(
            @PathVariable UUID patientId,
            @Valid @RequestBody AddMedicalHistoryRequest request) {
        return clinicalRecordService.addMedicalHistory(patientId, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/medical-history/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public void deleteMedicalHistory(@PathVariable UUID patientId, @PathVariable UUID entryId) {
        clinicalRecordService.deleteMedicalHistory(patientId, entryId);
    }
}
