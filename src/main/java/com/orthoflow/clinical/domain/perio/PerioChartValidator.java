package com.orthoflow.clinical.domain.perio;

import com.orthoflow.common.exception.ValidationException;

import java.util.List;
import java.util.Map;

/**
 * A chart is typed by a clinician in a hurry, autosaved on every keystroke and read months later
 * by someone else, so what is stored has to mean one thing. Every value is checked against the
 * ranges of the examination, not only against "is a number".
 */
public final class PerioChartValidator {

    private PerioChartValidator() {
    }

    public static void validate(Map<String, String> toothStatus, Map<String, ToothMeasurements> teeth) {
        for (Map.Entry<String, String> entry : toothStatus.entrySet()) {
            requireTooth(entry.getKey());
            if (entry.getValue() == null || !PerioSites.TOOTH_STATUSES.contains(entry.getValue())) {
                throw new ValidationException("Unknown status for tooth " + entry.getKey() + ": " + entry.getValue());
            }
        }
        for (Map.Entry<String, ToothMeasurements> entry : teeth.entrySet()) {
            String tooth = requireTooth(entry.getKey());
            ToothMeasurements measurements = entry.getValue();
            if (measurements == null || measurements.isEmpty()) {
                continue;
            }
            String status = toothStatus.get(tooth);
            if (PerioSites.MISSING.equals(status) || PerioSites.PONTIC.equals(status)) {
                throw new ValidationException("Tooth " + tooth + " is marked " + status + " and cannot have measurements.");
            }
            validateTooth(tooth, measurements);
        }
    }

    private static void validateTooth(String tooth, ToothMeasurements m) {
        if (m.mobility() != null && (m.mobility() < 0 || m.mobility() > PerioSites.MAX_MOBILITY)) {
            throw new ValidationException("Mobility of tooth " + tooth + " must be 0 to " + PerioSites.MAX_MOBILITY + ".");
        }
        if (m.furcation() != null) {
            List<String> allowed = PerioSites.furcationSitesOf(tooth);
            for (Map.Entry<String, String> f : m.furcation().entrySet()) {
                if (!allowed.contains(f.getKey())) {
                    throw new ValidationException("Tooth " + tooth + " has no furcation site " + f.getKey() + ".");
                }
                if (f.getValue() == null || !PerioSites.FURCATION_GRADES.contains(f.getValue())) {
                    throw new ValidationException("Furcation grade of tooth " + tooth + " must be I, II or III.");
                }
            }
        }
        range(tooth, "Pocket depth", m.pocketDepth(), 0, PerioSites.MAX_POCKET_DEPTH);
        range(tooth, "Recession", m.recession(), PerioSites.MIN_RECESSION, PerioSites.MAX_RECESSION);
        sites(tooth, "bleeding", m.bleeding());
        sites(tooth, "plaque", m.plaque());
        sites(tooth, "suppuration", m.suppuration());
    }

    private static void range(String tooth, String what, Map<String, Integer> values, int min, int max) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Integer> v : values.entrySet()) {
            if (!PerioSites.PROBING_SITES.contains(v.getKey())) {
                throw new ValidationException("Unknown site on tooth " + tooth + ": " + v.getKey());
            }
            if (v.getValue() == null || v.getValue() < min || v.getValue() > max) {
                throw new ValidationException(what + " of tooth " + tooth + " must be between " + min + " and " + max + " mm.");
            }
        }
    }

    private static void sites(String tooth, String what, List<String> sites) {
        if (sites == null) {
            return;
        }
        for (String site : sites) {
            if (!PerioSites.PROBING_SITES.contains(site)) {
                throw new ValidationException("Unknown " + what + " site on tooth " + tooth + ": " + site);
            }
        }
    }

    private static String requireTooth(String code) {
        if (!PerioSites.isTooth(code)) {
            throw new ValidationException("Unknown tooth: " + code);
        }
        return code;
    }
}
