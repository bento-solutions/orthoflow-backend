package com.orthoflow.inventory.domain.model;

/**
 * Why more of something was used than the bill of materials expects.
 *
 * <p>These exist so the leakage report can tell a bad morning from a real loss. Excess
 * that carries one of these is explained consumption: the material genuinely went into
 * the work. Only what is left unexplained, and outside the line's tolerance, is
 * attributed to anybody.
 */
public enum VarianceReason {

    /** Spilled, over-mixed, or set before it could be placed. */
    WASTAGE,

    /** Dropped or snapped before it reached the patient. */
    BREAKAGE,

    /**
     * The step had to be redone. Legitimate consumption and a quality signal, which is
     * why it is reported as a re-treatment rate rather than counted as a gap.
     */
    RE_TREATMENT,

    /** The patient moved, gagged or bled, and the material was lost to it. */
    PATIENT_FACTOR,

    /** Anything else; the note carries the explanation. */
    OTHER;

    /** Whether this reason should be kept out of the leakage figure entirely. */
    public boolean excludedFromLeakage() {
        return this == RE_TREATMENT;
    }
}
