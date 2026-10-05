package com.orthoflow.settings.application.dto;

import com.orthoflow.settings.domain.model.OpeningHours;
import com.orthoflow.settings.domain.model.PracticeProfile;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class PracticeProfileDtos {

    private PracticeProfileDtos() {
    }

    public record Profile(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String legalName,
            @Size(max = 30) String ice,
            @Size(max = 30) String taxId,
            @Size(max = 30) String patente,
            @Size(max = 30) String cnssNumber,
            @Size(max = 40) String rib,
            @Size(max = 20) String inpe,
            @Size(max = 500) String address,
            @Size(max = 100) String city,
            @Size(max = 40) String phone,
            @Email @Size(max = 255) String email,
            @Size(max = 255) String website,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 50) String timezone,
            @Pattern(regexp = "fr|en|ar") String defaultLanguage,
            UUID logoFileId) {

        public static Profile from(PracticeProfile p) {
            return new Profile(p.getName(), p.getLegalName(), p.getIce(), p.getTaxId(), p.getPatente(),
                    p.getCnssNumber(), p.getRib(), p.getInpe(), p.getAddress(), p.getCity(), p.getPhone(),
                    p.getEmail(), p.getWebsite(), p.getCurrency(), p.getTimezone(), p.getDefaultLanguage(),
                    p.getLogoFileId());
        }
    }

    public record Day(@Min(1) @Max(7) short weekday, boolean closed, @NotNull LocalTime openTime,
                      @NotNull LocalTime closeTime, LocalTime breakStart, LocalTime breakEnd) {

        public static Day from(OpeningHours h) {
            return new Day(h.getWeekday(), h.isClosed(), h.getOpenTime(), h.getCloseTime(), h.getBreakStart(), h.getBreakEnd());
        }

        @JsonIgnore
        @AssertTrue(message = "opening time must be before closing time")
        public boolean isOrderValid() {
            return closed || openTime == null || closeTime == null || openTime.isBefore(closeTime);
        }

        @JsonIgnore
        @AssertTrue(message = "break must be inside opening hours and have both ends")
        public boolean isBreakValid() {
            if (breakStart == null && breakEnd == null) return true;
            return breakStart != null && breakEnd != null && breakStart.isBefore(breakEnd)
                    && openTime != null && closeTime != null
                    && breakStart.isAfter(openTime) && breakEnd.isBefore(closeTime);
        }
    }

    public record Week(@NotNull @Size(min = 7, max = 7) List<@Valid Day> days) {
    }
}
