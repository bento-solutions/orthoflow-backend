package com.orthoflow.recall.application.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class RecallDtos {

    private RecallDtos() {
    }

    /**
     * The lists a front desk calls from. The first five are the general ones;
     * the last three are orthodontic: patients lost mid-treatment and retention
     * checks that fall due after the appliance comes off.
     */
    public enum Kind {
        NO_VISIT_1M(1), NO_VISIT_3M(3), NO_VISIT_6M(6),
        NOTHING_SCHEDULED_1M(1), NOTHING_SCHEDULED_12M(12),
        LOST_TO_FOLLOW_UP(0), RETENTION_DUE_6M(6), RETENTION_DUE_12M(12);

        private final int months;

        Kind(int months) {
            this.months = months;
        }

        public int months() {
            return months;
        }
    }

    public record Row(UUID patientId, String patientCode, String firstName, String lastName, String phone, String email,
                      UUID primaryPractitionerId, String primaryPractitionerName, OffsetDateTime lastVisit,
                      OffsetDateTime nextAppointment, int progress, int remaining, LocalDate dueSince, String note) {
    }

    /** Narrowing common to every list. {@code excludeNeverVisited} defaults on: people who never came are not "lost". */
    public record Filter(UUID practiceId, Kind kind, UUID practitionerId, Integer minProgress, Integer maxProgress,
                         boolean excludeNeverVisited, String sort) {
    }
}
