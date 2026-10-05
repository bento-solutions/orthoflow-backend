package com.orthoflow.scheduling.domain.model;

/**
 * The life of a visit: booked, confirmed by the patient, arrived at the
 * clinic, seated in a chair, done — or it did not happen. Statuses from
 * {@link #ARRIVED} on are the front desk's live view of the day; the timestamps
 * recorded on each transition are what doctor-time analytics are built from.
 */
public enum AppointmentStatus {
    SCHEDULED,
    CONFIRMED,
    /** Past its time and not here yet. Set by staff; informational. */
    LATE,
    ARRIVED,
    IN_CHAIR,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    /** A visit that occupies the clinic's time (the database constraints ignore the other two). */
    public boolean holdsASlot() {
        return this != CANCELLED && this != NO_SHOW;
    }

    /** Has not started yet. */
    public boolean isUpcoming() {
        return this == SCHEDULED || this == CONFIRMED || this == LATE;
    }
}
