package com.orthoflow.treatment.application.dto;

import com.orthoflow.treatment.domain.model.PatientTreatment;
import com.orthoflow.treatment.domain.model.PatientTreatmentStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public record PatientTreatmentResponse(
        UUID id,
        UUID patientId,
        TreatmentResponse treatment,
        String teeth,
        PatientTreatmentStatus status,
        int progress,
        String notes,
        String doctorName,
        UUID practitionerId,
        LocalDate startDate,
        LocalDate endDate,
        List<PatientTreatmentConsumableResponse> consumables,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static PatientTreatmentResponse from(PatientTreatment pt) {
        if (pt == null) return null;
        return new PatientTreatmentResponse(
                pt.getId(),
                pt.getPatientId(),
                TreatmentResponse.shallow(pt.getTreatment()),
                pt.getTeeth(),
                pt.getStatus(),
                pt.getProgress(),
                pt.getNotes(),
                pt.getDoctorName(),
                pt.getPractitionerId(),
                pt.getStartDate(),
                pt.getEndDate(),
                pt.getConsumables() == null ? List.of() :
                        pt.getConsumables().stream().map(PatientTreatmentConsumableResponse::from).collect(Collectors.toList()),
                pt.getCreatedAt(),
                pt.getUpdatedAt()
        );
    }
}
