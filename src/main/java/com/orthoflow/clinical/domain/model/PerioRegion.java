package com.orthoflow.clinical.domain.model;

import java.util.List;

/**
 * The part of the mouth a gum assessment speaks about: the whole mouth, or one
 * of the six sextants dentists chart periodontal status by. Gums are rarely
 * uniformly bad, and "periodontitis" on a mouth whose front teeth are healthy
 * is a misstatement the next practitioner would act on.
 */
public enum PerioRegion {
    WHOLE_MOUTH,
    UPPER_RIGHT("18", "17", "16", "15", "14"),
    UPPER_FRONT("13", "12", "11", "21", "22", "23"),
    UPPER_LEFT("24", "25", "26", "27", "28"),
    LOWER_LEFT("38", "37", "36", "35", "34"),
    LOWER_FRONT("33", "32", "31", "41", "42", "43"),
    LOWER_RIGHT("44", "45", "46", "47", "48");

    private final List<String> teeth;

    PerioRegion(String... teeth) {
        this.teeth = List.of(teeth);
    }

    /** Permanent teeth of the sextant; empty for the whole mouth. */
    public List<String> teeth() {
        return teeth;
    }
}
