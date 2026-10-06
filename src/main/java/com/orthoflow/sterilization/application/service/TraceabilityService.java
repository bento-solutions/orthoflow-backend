package com.orthoflow.sterilization.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.TraceEntry;
import com.orthoflow.sterilization.infrastructure.SterilizationItemJpaRepository;
import com.orthoflow.sterilization.infrastructure.SterilizationQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** The register read both ways: what touched a patient or a visit, and where one item has been. */
@Service
@RequiredArgsConstructor
public class TraceabilityService {

    private static final int ITEM_HISTORY_LIMIT = 500;

    private final SterilizationQuery query;
    private final SterilizationItemJpaRepository items;
    private final PatientLookup patients;

    @Transactional(readOnly = true)
    public List<TraceEntry> forPatient(UUID practiceId, UUID patientId) {
        if (!patients.exists(patientId)) {
            throw new NotFoundException("Patient not found");
        }
        return query.usedOnPatient(practiceId, patientId);
    }

    @Transactional(readOnly = true)
    public List<TraceEntry> forAppointment(UUID practiceId, UUID appointmentId) {
        return query.usedAtAppointment(practiceId, appointmentId);
    }

    @Transactional(readOnly = true)
    public List<TraceEntry> forItem(UUID practiceId, UUID itemId) {
        items.findByIdAndPracticeId(itemId, practiceId).orElseThrow(() -> new NotFoundException("Item not found"));
        return query.forItem(practiceId, itemId, ITEM_HISTORY_LIMIT);
    }
}
