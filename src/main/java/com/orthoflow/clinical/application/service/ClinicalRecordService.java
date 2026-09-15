package com.orthoflow.clinical.application.service;

import com.orthoflow.patient.domain.repository.PatientRepository;
import com.orthoflow.clinical.application.dto.*;
import com.orthoflow.clinical.domain.model.*;
import com.orthoflow.clinical.domain.repository.*;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes to the structured clinical record — tooth findings, notes, allergies,
 * medical history.
 *
 * <p>This is the layer every clinical write lands on, whatever drove it: a
 * click, a keyboard shortcut, or a voice command. The voice pipeline
 * deliberately stops one level above this and hands over a validated,
 * typed request; nothing here knows or cares that speech was involved beyond
 * stamping {@code source = "voice"} on the row for the audit trail.
 */
@Service
@RequiredArgsConstructor
public class ClinicalRecordService {

    private final DentalChartRepository dentalChartRepository;
    private final ToothFindingRepository toothFindingRepository;
    private final ClinicalNoteRepository clinicalNoteRepository;
    private final PatientAllergyRepository allergyRepository;
    private final MedicalHistoryRepository medicalHistoryRepository;
    private final ToothStateEventRepository toothStateEventRepository;
    private final PatientRepository patientRepository;

    // ── Tooth findings ──────────────────────────────────────────────────

    /**
     * Records one finding on one tooth, alongside whatever is already there.
     *
     * <p>Re-dictating a finding the tooth already carries updates that finding
     * rather than filing a duplicate — dictation repeats itself, and two
     * identical "recurrent caries on 16" rows are noise, not two observations.
     * The odontogram's single per-tooth status is recomputed from the tooth's
     * full finding set afterwards, so the chart stays consistent with the
     * record no matter what order findings arrived in.
     */
    @Transactional
    public ToothFindingResponse addFinding(UUID patientId, String fdi, AddToothFindingRequest request, UUID actorId) {
        return addFindingInternal(patientId, fdi, request, actorId);
    }

    /**
     * Records multiple findings on a single tooth in one transaction. This is
     * the normal voice dictation path — "old crown, recurrent caries, crown
     * needs replacement" is three findings, but they must land together or
     * not at all so a partial failure does not leave the record half-written.
     */
    @Transactional
    public List<ToothFindingResponse> addFindingsBatch(UUID patientId, String fdi,
                                                        List<AddToothFindingRequest> requests, UUID actorId) {
        List<ToothFindingResponse> results = new java.util.ArrayList<>();
        for (AddToothFindingRequest request : requests) {
            results.add(addFindingInternal(patientId, fdi, request, actorId));
        }
        return results;
    }

    /**
     * Retracts multiple findings in one transaction. Used by the correction
     * path ("no, actually…") where superseded findings are withdrawn and
     * replacements recorded together, so the record never passes through a
     * state where the tooth carries neither.
     */
    @Transactional
    public List<ToothFindingResponse> retractFindingsBatch(List<UUID> findingIds, UUID actorId) {
        List<ToothFindingResponse> results = new java.util.ArrayList<>();
        for (UUID findingId : findingIds) {
            results.add(changeFindingStatus(findingId, FindingStatus.RETRACTED, actorId));
        }
        return results;
    }

    /**
     * Internal: performs the addFinding logic without starting its own
     * transaction — it participates in whatever transaction is already open.
     */
    private ToothFindingResponse addFindingInternal(UUID patientId, String fdi, AddToothFindingRequest request, UUID actorId) {
        DentalChart chart = requireChart(patientId);
        String code = request.getFindingCode();

        FindingCatalog.Definition definition = FindingCatalog.get(code)
                .orElseThrow(() -> new ValidationException(
                        "Unknown finding code '" + code + "'. It is not in the clinical finding catalog."));

        validateFdi(fdi);

        ToothFinding finding = toothFindingRepository
                .findActiveByChartFdiAndCode(chart.getId(), fdi, code)
                .orElseGet(() -> ToothFinding.builder()
                        .chart(chart)
                        .fdi(fdi)
                        .findingCode(code)
                        .kind(definition.kind())
                        .recordedBy(actorId)
                        .build());

        finding.setKind(definition.kind());
        finding.setSurface(blankToNull(request.getSurface()));
        finding.setSeverity(parseSeverity(request.getSeverity()));
        if (blankToNull(request.getNote()) != null) {
            finding.setNote(request.getNote());
        }
        finding.setSource(request.getSource());
        finding.setSessionId(request.getSessionId());
        finding.setStatus(FindingStatus.ACTIVE);

        ToothFinding saved = toothFindingRepository.save(finding);
        recomputePrimaryStatus(chart, fdi, actorId, request.getSource(), request.getNote());
        return toResponse(saved);
    }

    /**
     * "No, not that" — the doctor is telling us the system misheard, so the
     * finding is withdrawn rather than deleted. RESOLVED is the other exit and
     * means something different: it was true, and has since been treated.
     */
    /**
     * The REST entry point: the finding must belong to {@code patientId} (the
     * one in the URL), or it is a 404 — see {@link #assertBelongsToPatient}.
     * The voice pipeline uses the {@code patientId}-less overload below, since
     * it is already operating inside one patient's confirmed session.
     */
    @Transactional
    public ToothFindingResponse changeFindingStatus(UUID patientId, UUID findingId, FindingStatus newStatus, UUID actorId) {
        ToothFinding finding = toothFindingRepository.findById(findingId)
                .orElseThrow(() -> new NotFoundException("Finding not found: " + findingId));
        assertBelongsToPatient(patientId, finding.getChart() == null ? null : finding.getChart().getPatientId(),
                "Finding not found: " + findingId);
        return applyFindingStatus(finding, newStatus, actorId);
    }

    @Transactional
    public ToothFindingResponse changeFindingStatus(UUID findingId, FindingStatus newStatus, UUID actorId) {
        ToothFinding finding = toothFindingRepository.findById(findingId)
                .orElseThrow(() -> new NotFoundException("Finding not found: " + findingId));
        return applyFindingStatus(finding, newStatus, actorId);
    }

    private ToothFindingResponse applyFindingStatus(ToothFinding finding, FindingStatus newStatus, UUID actorId) {
        finding.setStatus(newStatus);
        ToothFinding saved = toothFindingRepository.save(finding);
        recomputePrimaryStatus(finding.getChart(), finding.getFdi(), actorId, "voice", null);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ToothFindingResponse> listFindings(UUID patientId) {
        return dentalChartRepository.findByPatientId(patientId)
                .map(chart -> toothFindingRepository.findActiveByChart(chart.getId()).stream()
                        .map(this::toResponse)
                        .toList())
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public List<ToothFindingResponse> listFindingsForTooth(UUID patientId, String fdi) {
        return dentalChartRepository.findByPatientId(patientId)
                .map(chart -> toothFindingRepository.findActiveByChartAndFdi(chart.getId(), fdi).stream()
                        .map(this::toResponse)
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Keeps {@code tooth_states.status} — the one value the 2D odontogram and
     * the 3D viewer colour a tooth from — in step with the tooth's findings,
     * and records the change in the existing append-only tooth audit trail so
     * a voice-driven write is traceable through exactly the same log as a
     * click-driven one.
     */
    private void recomputePrimaryStatus(DentalChart chart, String fdi, UUID actorId, String source, String note) {
        List<String> activeCodes = toothFindingRepository.findActiveByChartAndFdi(chart.getId(), fdi).stream()
                .map(ToothFinding::getFindingCode)
                .toList();
        String status = FindingCatalog.primaryStatus(activeCodes);

        ToothState existing = chart.getToothStates().stream()
                .filter(t -> t.getFdi().equals(fdi))
                .findFirst()
                .orElse(null);

        String previousStatus = existing != null ? existing.getStatus() : null;
        if (existing != null) {
            existing.setStatus(status);
        } else {
            chart.getToothStates().add(ToothState.builder()
                    .chart(chart)
                    .fdi(fdi)
                    .status(status)
                    .build());
        }
        dentalChartRepository.save(chart);

        // Only log a state event when the painted status actually moved.
        // A tooth accumulating "sensitivity" then "monitor" changes the record
        // but not the chart, and a trail full of no-op transitions is harder to
        // read than one that shows only real changes.
        if (!status.equals(previousStatus)) {
            toothStateEventRepository.save(ToothStateEvent.builder()
                    .patientId(chart.getPatientId())
                    .fdi(fdi)
                    .previousStatus(previousStatus)
                    .newStatus(status)
                    .notes(note)
                    .actorId(actorId)
                    .source(source)
                    .build());
        }
    }

    // ── Clinical notes ──────────────────────────────────────────────────

    @Transactional
    public ClinicalNoteResponse addNote(UUID patientId, CreateClinicalNoteRequest request, UUID actorId) {
        requirePatient(patientId);
        NoteCategory category = parseEnum(NoteCategory.class, request.getCategory(), "note category");
        String fdi = blankToNull(request.getFdi());
        if (fdi != null) validateFdi(fdi);

        ClinicalNote note = clinicalNoteRepository.save(ClinicalNote.builder()
                .patientId(patientId)
                .category(category)
                .content(request.getContent())
                .fdi(fdi)
                .authorId(actorId)
                .source(request.getSource())
                .sessionId(request.getSessionId())
                .build());

        return toResponse(note);
    }

    @Transactional
    public void deleteNote(UUID patientId, UUID noteId) {
        ClinicalNote note = clinicalNoteRepository.findById(noteId)
                .orElseThrow(() -> new NotFoundException("Clinical note not found: " + noteId));
        assertBelongsToPatient(patientId, note.getPatientId(), "Clinical note not found: " + noteId);
        note.setDeletedAt(OffsetDateTime.now());
        clinicalNoteRepository.save(note);
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteResponse> listNotes(UUID patientId) {
        return clinicalNoteRepository.findByPatient(patientId).stream().map(this::toResponse).toList();
    }

    // ── Allergies ───────────────────────────────────────────────────────

    /**
     * Allergies are matched case-insensitively on substance: "Penicillin" and
     * "penicillin" are one allergy, and a dictated one arrives in whatever
     * case the recogniser chose. Re-stating a known allergy enriches it
     * (reaction, severity) rather than creating a second row.
     */
    @Transactional
    public AllergyResponse addAllergy(UUID patientId, AddAllergyRequest request, UUID actorId) {
        requirePatient(patientId);
        String substance = request.getSubstance().trim();

        PatientAllergy allergy = allergyRepository.findByPatient(patientId).stream()
                .filter(a -> a.getSubstance().equalsIgnoreCase(substance))
                .findFirst()
                .orElseGet(() -> PatientAllergy.builder()
                        .patientId(patientId)
                        .substance(substance)
                        .recordedBy(actorId)
                        .build());

        if (blankToNull(request.getReaction()) != null) allergy.setReaction(request.getReaction());
        if (parseSeverity(request.getSeverity()) != null) allergy.setSeverity(parseSeverity(request.getSeverity()));
        allergy.setSource(request.getSource());
        allergy.setSessionId(request.getSessionId());

        return toResponse(allergyRepository.save(allergy));
    }

    @Transactional
    public void deleteAllergy(UUID patientId, UUID allergyId) {
        PatientAllergy allergy = allergyRepository.findById(allergyId)
                .orElseThrow(() -> new NotFoundException("Allergy not found: " + allergyId));
        assertBelongsToPatient(patientId, allergy.getPatientId(), "Allergy not found: " + allergyId);
        allergy.setDeletedAt(OffsetDateTime.now());
        allergyRepository.save(allergy);
    }

    @Transactional(readOnly = true)
    public List<AllergyResponse> listAllergies(UUID patientId) {
        return allergyRepository.findByPatient(patientId).stream().map(this::toResponse).toList();
    }

    // ── Medical history ─────────────────────────────────────────────────

    @Transactional
    public MedicalHistoryResponse addMedicalHistory(UUID patientId, AddMedicalHistoryRequest request, UUID actorId) {
        requirePatient(patientId);
        MedicalHistoryCategory category =
                parseEnum(MedicalHistoryCategory.class, request.getCategory(), "medical history category");
        String label = request.getLabel().trim();

        MedicalHistoryEntry entry = medicalHistoryRepository.findByPatient(patientId).stream()
                .filter(e -> e.getCategory() == category && e.getLabel().equalsIgnoreCase(label))
                .findFirst()
                .orElseGet(() -> MedicalHistoryEntry.builder()
                        .patientId(patientId)
                        .category(category)
                        .label(label)
                        .recordedBy(actorId)
                        .build());

        if (blankToNull(request.getDetail()) != null) entry.setDetail(request.getDetail());
        entry.setSource(request.getSource());
        entry.setSessionId(request.getSessionId());

        return toResponse(medicalHistoryRepository.save(entry));
    }

    @Transactional
    public void deleteMedicalHistory(UUID patientId, UUID entryId) {
        MedicalHistoryEntry entry = medicalHistoryRepository.findById(entryId)
                .orElseThrow(() -> new NotFoundException("Medical history entry not found: " + entryId));
        assertBelongsToPatient(patientId, entry.getPatientId(), "Medical history entry not found: " + entryId);
        entry.setDeletedAt(OffsetDateTime.now());
        medicalHistoryRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<MedicalHistoryResponse> listMedicalHistory(UUID patientId) {
        return medicalHistoryRepository.findByPatient(patientId).stream().map(this::toResponse).toList();
    }

    // ── Aggregate read ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PatientClinicalRecordResponse getRecord(UUID patientId) {
        requirePatient(patientId);
        return PatientClinicalRecordResponse.builder()
                .findings(listFindings(patientId))
                .notes(listNotes(patientId))
                .allergies(listAllergies(patientId))
                .medicalHistory(listMedicalHistory(patientId))
                .build();
    }

    /** Everything one dictated examination produced, for its review summary. */
    @Transactional(readOnly = true)
    public PatientClinicalRecordResponse getSessionRecord(UUID sessionId) {
        return PatientClinicalRecordResponse.builder()
                .findings(toothFindingRepository.findBySession(sessionId).stream().map(this::toResponse).toList())
                .notes(clinicalNoteRepository.findBySession(sessionId).stream().map(this::toResponse).toList())
                .allergies(allergyRepository.findBySession(sessionId).stream().map(this::toResponse).toList())
                .medicalHistory(medicalHistoryRepository.findBySession(sessionId).stream().map(this::toResponse).toList())
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private DentalChart requireChart(UUID patientId) {
        requirePatient(patientId);
        return dentalChartRepository.findByPatientId(patientId)
                .orElseGet(() -> dentalChartRepository.save(DentalChart.builder()
                        .patientId(patientId)
                        .chartType(ChartType.adult)
                        .build()));
    }

    private void requirePatient(UUID patientId) {
        if (patientRepository.findById(patientId).isEmpty()) {
            throw new NotFoundException("Patient not found: " + patientId);
        }
    }

    /**
     * These records are addressed as {@code /patients/{patientId}/clinical-record/…/{childId}},
     * but the child id alone is unique, so without this a note, allergy,
     * finding or history entry belonging to patient A could be mutated or
     * deleted through a URL naming patient B (audit M3). A mismatch is a 404,
     * not a 403 — the resource genuinely does not exist under that patient.
     */
    private static void assertBelongsToPatient(UUID expectedPatientId, UUID actualPatientId, String notFoundMessage) {
        if (actualPatientId == null || !actualPatientId.equals(expectedPatientId)) {
            throw new NotFoundException(notFoundMessage);
        }
    }

    /**
     * FDI codes only, and only ones that name a real tooth. A voice command
     * that resolved to "58" must be refused here even if every layer above it
     * was happy, because this is the last point before it becomes a clinical
     * record.
     */
    private void validateFdi(String fdi) {
        if (fdi == null || !fdi.matches("[1-8][1-8]")) {
            throw new ValidationException("Invalid FDI tooth code: " + fdi);
        }
        int quadrant = Character.getNumericValue(fdi.charAt(0));
        int position = Character.getNumericValue(fdi.charAt(1));
        boolean permanent = quadrant <= 4 && position <= 8;
        boolean deciduous = quadrant >= 5 && position <= 5;
        if (!permanent && !deciduous) {
            throw new ValidationException("Invalid FDI tooth code: " + fdi);
        }
    }

    private Severity parseSeverity(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : parseEnum(Severity.class, normalized, "severity");
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid " + label + ": " + value);
        }
    }

    private String blankToNull(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
    }

    private ToothFindingResponse toResponse(ToothFinding f) {
        return ToothFindingResponse.builder()
                .id(f.getId())
                .fdi(f.getFdi())
                .findingCode(f.getFindingCode())
                .kind(f.getKind().name())
                .surface(f.getSurface())
                .severity(f.getSeverity() != null ? f.getSeverity().name() : null)
                .note(f.getNote())
                .status(f.getStatus().name())
                .source(f.getSource())
                .sessionId(f.getSessionId())
                .createdAt(f.getCreatedAt())
                .build();
    }

    private ClinicalNoteResponse toResponse(ClinicalNote n) {
        return ClinicalNoteResponse.builder()
                .id(n.getId())
                .category(n.getCategory().name())
                .content(n.getContent())
                .fdi(n.getFdi())
                .authorId(n.getAuthorId())
                .source(n.getSource())
                .sessionId(n.getSessionId())
                .createdAt(n.getCreatedAt())
                .build();
    }

    private AllergyResponse toResponse(PatientAllergy a) {
        return AllergyResponse.builder()
                .id(a.getId())
                .substance(a.getSubstance())
                .reaction(a.getReaction())
                .severity(a.getSeverity() != null ? a.getSeverity().name() : null)
                .source(a.getSource())
                .sessionId(a.getSessionId())
                .recordedAt(a.getRecordedAt())
                .build();
    }

    private MedicalHistoryResponse toResponse(MedicalHistoryEntry e) {
        return MedicalHistoryResponse.builder()
                .id(e.getId())
                .category(e.getCategory().name())
                .label(e.getLabel())
                .detail(e.getDetail())
                .source(e.getSource())
                .sessionId(e.getSessionId())
                .recordedAt(e.getRecordedAt())
                .build();
    }
}
