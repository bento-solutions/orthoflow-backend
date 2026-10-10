package com.orthoflow.clinical.application.service;

import com.orthoflow.clinical.application.dto.PeriodontalAssessmentResponse;
import com.orthoflow.clinical.application.dto.PeriodontalStatusResponse;
import com.orthoflow.clinical.application.dto.RecordPeriodontalRequest;
import com.orthoflow.clinical.domain.model.PerioCondition;
import com.orthoflow.clinical.domain.model.PerioRegion;
import com.orthoflow.clinical.domain.model.PeriodontalAssessment;
import com.orthoflow.clinical.domain.repository.PeriodontalAssessmentRepository;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.domain.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Gum state, kept apart from the tooth findings: it belongs to a region of the
 * mouth, not to a tooth, and it has a history of its own.
 */
@Service
@RequiredArgsConstructor
public class PeriodontalService {

    private final PeriodontalAssessmentRepository assessments;
    private final PatientRepository patients;

    @Transactional
    public PeriodontalAssessmentResponse record(UUID patientId, RecordPeriodontalRequest request, UUID actorId) {
        requirePatient(patientId);
        PerioRegion region = parse(PerioRegion.class, request.getRegion(), "region");
        PerioCondition condition = parse(PerioCondition.class, request.getCondition(), "gum condition");

        Integer stage = request.getStage();
        if (stage != null) {
            if (condition != PerioCondition.PERIODONTITIS) {
                throw new ValidationException("A stage only applies to periodontitis.");
            }
            if (stage < 1 || stage > 4) {
                throw new ValidationException("The periodontitis stage is I to IV (1 to 4).");
            }
        }

        LocalDate today = LocalDate.now();
        LocalDate on = request.getAssessedOn() == null ? today : request.getAssessedOn();
        if (on.isAfter(today)) {
            throw new ValidationException("The date of an assessment cannot be in the future.");
        }

        String note = request.getNote() == null || request.getNote().isBlank() ? null : request.getNote().trim();
        PeriodontalAssessment saved = assessments.save(PeriodontalAssessment.builder()
                .patientId(patientId)
                .region(region)
                .condition(condition)
                .stage(stage)
                .note(note)
                .assessedOn(on)
                .recordedBy(actorId)
                .source(request.getSource())
                .build());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PeriodontalStatusResponse status(UUID patientId) {
        requirePatient(patientId);
        // Newest first, so the first row seen for a region is its current state.
        List<PeriodontalAssessment> all = assessments.findByPatient(patientId);
        Map<PerioRegion, PeriodontalAssessment> latest = new LinkedHashMap<>();
        for (PeriodontalAssessment a : all) {
            latest.putIfAbsent(a.getRegion(), a);
        }
        return PeriodontalStatusResponse.builder()
                .current(latest.values().stream().map(this::toResponse).toList())
                .history(all.stream().map(this::toResponse).toList())
                .build();
    }

    private void requirePatient(UUID patientId) {
        if (patients.findById(patientId).isEmpty()) {
            throw new NotFoundException("Patient not found: " + patientId);
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationException("Unknown " + what + ": " + value);
        }
    }

    private PeriodontalAssessmentResponse toResponse(PeriodontalAssessment a) {
        return PeriodontalAssessmentResponse.builder()
                .id(a.getId())
                .region(a.getRegion().name())
                .condition(a.getCondition().name())
                .stage(a.getStage())
                .note(a.getNote())
                .assessedOn(a.getAssessedOn())
                .source(a.getSource())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
