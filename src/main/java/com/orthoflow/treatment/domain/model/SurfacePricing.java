package com.orthoflow.treatment.domain.model;

import com.orthoflow.common.exception.ValidationException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * How the faces of a tooth turn into a price.
 *
 * <p>The taxonomy is the standard charting one: five faces that exclude one
 * another (mesial, distal, buccal, lingual, and the biting face, occlusal on
 * a back tooth and incisal on a front one) plus the cervical zone at the
 * gumline. "Proximal" is not a sixth: it is the collective word for mesial and
 * distal, so it is read as those two. A lesion therefore has exactly one set of
 * faces and one price, however it was worded.
 *
 * <p>A treatment is priced by how many faces it covers. With no surface, or no
 * rule for that many faces, the treatment's base price stands.
 */
public final class SurfacePricing {

    public static final int MAX_FACES = 5;

    private SurfacePricing() {}

    /** The distinct faces named by a stored surface ("mesial-occlusal", "proximal"). */
    public static Set<String> faces(String surface) {
        Set<String> faces = new LinkedHashSet<>();
        if (surface == null || surface.isBlank()) return faces;
        for (String part : surface.toLowerCase(Locale.ROOT).split("-")) {
            switch (part.trim()) {
                case "mesial", "distal", "buccal", "lingual", "cervical" -> faces.add(part.trim());
                case "occlusal", "incisal" -> faces.add("biting");
                case "proximal", "interproximal", "approximal" -> {
                    faces.add("mesial");
                    faces.add("distal");
                }
                case "" -> { }
                default -> throw new ValidationException("Unknown tooth surface: " + part);
            }
        }
        return faces;
    }

    public static int faceCount(String surface) {
        return Math.min(faces(surface).size(), MAX_FACES);
    }

    /** What a price was derived from, so the screen can say why it is what it is. */
    public record Quote(BigDecimal price, int faceCount, String basis) {}

    /**
     * The rule for exactly this many faces, else the nearest rule below it (a
     * four-face restoration with a tariff up to three is charged the
     * three-face price, never less), else the base price.
     */
    public static Quote quote(BigDecimal basePrice, Collection<TreatmentSurfacePrice> rules, String surface) {
        int count = faceCount(surface);
        if (count == 0) return new Quote(basePrice, 0, "BASE");
        Optional<TreatmentSurfacePrice> rule = rules.stream()
                .filter(r -> r.getSurfaceCount() <= count)
                .max(java.util.Comparator.comparingInt(TreatmentSurfacePrice::getSurfaceCount));
        return rule.map(r -> new Quote(r.getPrice(), count, "FACES_" + r.getSurfaceCount()))
                .orElseGet(() -> new Quote(basePrice, count, "BASE"));
    }
}
