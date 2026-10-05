package com.orthoflow.lab.domain.model;

import java.util.Set;

/**
 * Where a piece is on its way from the lab to the patient's mouth. REMAKE is the
 * lab getting it wrong (or the fit being off): it goes back out, so it can only be
 * followed by a fresh SENT or IN_PROGRESS.
 */
public enum LabStatus {
    SENT, IN_PROGRESS, RECEIVED, FITTED, REMAKE;

    public Set<LabStatus> next() {
        return switch (this) {
            case SENT -> Set.of(IN_PROGRESS, RECEIVED);
            case IN_PROGRESS -> Set.of(RECEIVED);
            case RECEIVED -> Set.of(FITTED, REMAKE);
            case FITTED -> Set.of(REMAKE);
            case REMAKE -> Set.of(SENT, IN_PROGRESS);
        };
    }

    /** Still at the lab: the order is waiting on them. */
    public boolean isOutstanding() {
        return this == SENT || this == IN_PROGRESS || this == REMAKE;
    }
}
