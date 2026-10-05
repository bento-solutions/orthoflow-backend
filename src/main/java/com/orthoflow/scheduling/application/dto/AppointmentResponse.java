package com.orthoflow.scheduling.application.dto;

import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class AppointmentResponse {
    private UUID id;
    private UUID patientId;
    private String patientName;
    private OffsetDateTime dateTime;
    private UUID chairId;
    private String chairName;
    private UUID practitionerId;
    private String practitionerName;
    private String practitionerColor;
    private int durationMinutes;
    private String type;
    private AppointmentStatus status;
    private String notes;
    private Integer applianceStep;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
