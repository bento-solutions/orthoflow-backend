package com.orthoflow.clinical.domain.model;

/**
 * How sure the dentist is of a finding. The clinical referential asks that a state stay "suspected"
 * until the dentist has confirmed it ("suspicion de carie", "fracture probable"): a suspicion
 * recorded as a fact is a false statement in a clinical record.
 *
 * <p>A suspected finding is kept, shown and printed as one, but it does not drive the chart's single
 * painted status ({@link FindingCatalog#primaryStatus}); confirming it, which is the same finding
 * said again with no doubt, does.
 */
public enum FindingCertainty {
    CONFIRMED,
    SUSPECTED
}
