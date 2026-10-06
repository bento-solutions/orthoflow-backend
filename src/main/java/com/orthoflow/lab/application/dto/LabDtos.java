package com.orthoflow.lab.application.dto;

import com.orthoflow.lab.domain.model.LabItemType;
import com.orthoflow.lab.domain.model.LabStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class LabDtos {

    private LabDtos() {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "LabOrderRequest")
    public record Request(@NotNull UUID patientId, @NotNull UUID labId, UUID practitionerId, @NotNull LabItemType itemType,
                          @Size(max = 500) String description, LocalDate sentDate, LocalDate dueDate, boolean urgent,
                          @DecimalMin("0.00") BigDecimal cost, UUID fittingAppointmentId, String notes) {
    }

    public record Transition(@NotNull LabStatus status, LocalDate date) {
    }

    /** {@code warnings}: FITTING_BEFORE_DUE, OVERDUE, NOT_RECEIVED_BEFORE_FITTING. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "LabOrderView")
    public record View(UUID id, UUID patientId, String patientName, UUID labId, String labName, UUID practitionerId,
                       String practitionerName, LabItemType itemType, String description, LocalDate sentDate, LocalDate dueDate,
                       LabStatus status, boolean urgent, BigDecimal cost, UUID fittingAppointmentId, OffsetDateTime fittingAt,
                       LocalDate receivedDate, LocalDate fittedDate, String notes, List<String> warnings,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record Lab(UUID id, String name, String phone, String email) {
    }

    public record Filter(UUID practiceId, List<LabStatus> statuses, LocalDate dueFrom, LocalDate dueTo, LocalDate updatedFrom,
                         LocalDate updatedTo, boolean urgentOnly, UUID patientId, UUID labId, boolean overdueOnly, String search) {
    }
}
