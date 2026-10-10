package com.orthoflow.clinical.domain.model;

/**
 * Where the work on a tooth was done. The distinction is what lets a chart
 * for a patient who changed dentists say "amalgam, placed elsewhere" instead
 * of implying this clinic did it, and what the treatment passport groups by.
 */
public enum FindingOrigin {
    THIS_CLINIC,
    EXTERNAL
}
