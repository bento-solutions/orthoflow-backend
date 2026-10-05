package com.orthoflow.patient.application.service;

import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.application.service.PatientRecordMover.ChartChoice;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Merging a duplicate into the patient who stays. One transaction: every record
 * of the duplicate is re-pointed, the gaps in the surviving file are filled from
 * the duplicate, and the duplicate is archived — not deleted — pointing at the
 * survivor, with an activity entry on both. If anything fails, nothing moved.
 */
@Service
@RequiredArgsConstructor
public class PatientMergeService {

    public record Request(@NotNull UUID sourceId, ChartChoice dentalChartFrom, List<String> preferSourceFields) {
    }

    public record Preview(UUID sourceId, UUID targetId, Map<String, Integer> recordsToMove, boolean bothHaveDentalCharts,
                          Map<String, String[]> fieldDifferences) {
    }

    public record Result(UUID targetId, UUID sourceId, Map<String, Integer> moved) {
    }

    /** The fields staff may pick from; each knows how to read and write itself. */
    private record Field(String name, Function<Patient, Object> get, BiConsumer<Patient, Object> set) {
    }

    private static final List<Field> FIELDS = List.of(
            new Field("email", Patient::getEmail, (p, v) -> p.setEmail((String) v)),
            new Field("phone", Patient::getPhone, (p, v) -> p.setPhone((String) v)),
            new Field("cin", Patient::getCin, (p, v) -> p.setCin((String) v)),
            new Field("address", Patient::getAddress, (p, v) -> p.setAddress((String) v)),
            new Field("dateOfBirth", Patient::getDateOfBirth, (p, v) -> p.setDateOfBirth((java.time.LocalDate) v)),
            new Field("gender", Patient::getGender, (p, v) -> p.setGender((String) v)),
            new Field("guardianName", Patient::getGuardianName, (p, v) -> p.setGuardianName((String) v)),
            new Field("guardianPhone", Patient::getGuardianPhone, (p, v) -> p.setGuardianPhone((String) v)),
            new Field("insuranceProvider", Patient::getInsuranceProvider, (p, v) -> p.setInsuranceProvider((String) v)),
            new Field("insuranceNumber", Patient::getInsuranceNumber, (p, v) -> p.setInsuranceNumber((String) v)),
            new Field("occupation", Patient::getOccupation, (p, v) -> p.setOccupation((String) v)),
            new Field("referralSource", Patient::getReferralSource, (p, v) -> p.setReferralSource((String) v)),
            new Field("insurerId", Patient::getInsurerId, (p, v) -> p.setInsurerId((UUID) v)),
            new Field("primaryPractitionerId", Patient::getPrimaryPractitionerId, (p, v) -> p.setPrimaryPractitionerId((UUID) v)),
            new Field("photoFileId", Patient::getPhotoFileId, (p, v) -> p.setPhotoFileId((UUID) v)));

    private final PatientRepository patients;
    private final PatientRecordMover mover;
    private final JdbcTemplate jdbc;
    private final ActivityLog activityLog;
    private final LiveEventPublisher liveEvents;

    @Transactional(readOnly = true)
    public Preview preview(UUID practiceId, UUID targetId, UUID sourceId) {
        Patient target = load(practiceId, targetId);
        Patient source = load(practiceId, sourceId);
        Map<String, String[]> differences = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            Object t = f.get().apply(target);
            Object s = f.get().apply(source);
            if (s != null && !Objects.equals(t, s)) {
                differences.put(f.name(), new String[]{t == null ? null : String.valueOf(t), String.valueOf(s)});
            }
        }
        return new Preview(sourceId, targetId, mover.preview(sourceId), mover.bothHaveDentalCharts(sourceId, targetId), differences);
    }

    @Transactional
    public Result merge(UUID practiceId, UUID actorId, UUID targetId, Request r) {
        Patient target = load(practiceId, targetId);
        Patient source = load(practiceId, r.sourceId());
        Set<String> prefer = r.preferSourceFields() == null ? Set.of() : new HashSet<>(r.preferSourceFields());
        for (String name : prefer) {
            if (FIELDS.stream().noneMatch(f -> f.name().equals(name))) {
                throw new ValidationException("Unknown field '" + name + "'");
            }
        }

        // Unique columns first: the duplicate gives up its email so the survivor can take it.
        String sourceEmail = source.getEmail();
        jdbc.update("UPDATE patients SET email = NULL WHERE id = ?", source.getId());

        Map<String, Integer> moved = mover.move(source.getId(), target.getId(), r.dentalChartFrom());

        for (Field f : FIELDS) {
            Object current = f.get().apply(target);
            Object incoming = "email".equals(f.name()) ? sourceEmail : f.get().apply(source);
            boolean empty = current == null || (current instanceof String s && s.isBlank());
            if (incoming != null && (empty || prefer.contains(f.name()))) {
                f.set().accept(target, incoming);
            }
        }
        patients.save(target);

        jdbc.update("UPDATE patients SET deleted_at = ?, deleted_by = ?, merged_into_id = ?, email = NULL, updated_at = now() WHERE id = ?",
                OffsetDateTime.now(), actorId, target.getId(), source.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sourceId", source.getId().toString());
        detail.put("sourceCode", source.getPatientCode());
        detail.put("moved", moved.toString());
        activityLog.record(practiceId, "PATIENT", target.getId(), "MERGED_IN", detail);
        activityLog.record(practiceId, "PATIENT", source.getId(), "MERGED_INTO", Map.of("targetId", target.getId().toString(),
                "targetCode", target.getPatientCode()));
        liveEvents.publish(practiceId, "patient", target.getId());
        return new Result(target.getId(), source.getId(), moved);
    }

    private Patient load(UUID practiceId, UUID id) {
        Patient p = patients.findById(id).orElseThrow(() -> new NotFoundException("Patient not found"));
        if (!practiceId.equals(p.getPracticeId())) {
            throw new NotFoundException("Patient not found");
        }
        if (p.getMergedIntoId() != null) {
            throw new ConflictException("This patient was already merged into another record");
        }
        return p;
    }
}
