package com.orthoflow.scheduling.application.port;

import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

/** When the clinic is open on a weekday (ISO: 1 = Monday), supplied by the practice settings. Empty when closed. */
public interface OpeningHoursProvider {

    record Day(LocalTime open, LocalTime close, LocalTime breakStart, LocalTime breakEnd) {
    }

    Optional<Day> forWeekday(UUID practiceId, int isoWeekday);
}
