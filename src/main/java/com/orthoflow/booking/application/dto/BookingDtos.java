package com.orthoflow.booking.application.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "BookingSettings")
    public record Settings(boolean enabled, @Min(0) int leadTimeHours, @Min(1) @Max(365) int maxDaysAhead,
                           @Min(5) @Max(60) int slotStepMinutes, boolean autoConfirm) {
    }

    // ── Public side ──
    public record PublicType(UUID id, String nameFr, String nameEn, String nameAr, String color, int durationMinutes) {
    }

    public record PublicPractitioner(UUID id, String name, String color) {
    }

    /**
     * What the booking page may show: the clinic's name and what can be booked. No patient data, ever.
     * {@code timeZone} is the clinic's zone (for example {@code Africa/Casablanca}): the free times are
     * clock times in that zone, and the page needs it to send a moment the server reads the same way.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "BookingPublicInfo")
    public record PublicInfo(String clinicName, String phone, String city, List<PublicType> types, List<PublicPractitioner> practitioners,
                             int maxDaysAhead, String defaultLanguage, String timeZone) {
    }

    public record Availability(Map<LocalDate, List<String>> days) {
    }

    public record Submit(@NotNull UUID appointmentTypeId, UUID practitionerId, @NotNull OffsetDateTime startsAt,
                         @NotBlank @Size(max = 255) String firstName, @NotBlank @Size(max = 255) String lastName,
                         @Size(max = 40) String phone, @Email @Size(max = 255) String email, @Past LocalDate dateOfBirth,
                         @Size(max = 1000) String note, @Pattern(regexp = "fr|en|ar") String language,
                         @AssertTrue(message = "consent is required") boolean consent,
                         /** A hidden field a person never fills; a bot does. */ String website) {
    }

    /** Deliberately says nothing about the request beyond its reference. */
    public record Received(String reference) {
    }

    // ── Staff side ──
    public record RequestView(UUID id, UUID appointmentTypeId, String typeName, UUID practitionerId, String practitionerName,
                              OffsetDateTime startsAt, int durationMinutes, String firstName, String lastName, String phone,
                              String email, LocalDate dateOfBirth, String note, String language, String status, UUID patientId,
                              UUID appointmentId, String declineReason, OffsetDateTime createdAt, boolean slotStillFree) {
    }

    public record Confirm(UUID patientId, UUID practitionerId, UUID chairId, OffsetDateTime startsAt, Boolean ignoreBlocks) {
    }

    public record Decline(@Size(max = 500) String reason) {
    }
}
