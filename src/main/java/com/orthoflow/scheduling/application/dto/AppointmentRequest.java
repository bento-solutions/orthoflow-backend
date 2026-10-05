package com.orthoflow.scheduling.application.dto;

import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class AppointmentRequest {
    private UUID patientId;
    private OffsetDateTime dateTime;
    private UUID chairId;
    private UUID practitionerId;
    private Integer durationMinutes;
    private String type;
    private AppointmentStatus status;
    private String notes;
    private Integer applianceStep;
}
