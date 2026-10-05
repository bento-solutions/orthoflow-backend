package com.orthoflow.lab.application.port;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** What a lab order needs to know about the appointment it is for, supplied by scheduling. */
public interface AppointmentSlots {

    record Slot(UUID id, UUID practiceId, UUID patientId, OffsetDateTime dateTime) {
    }

    Optional<Slot> find(UUID appointmentId);
}
