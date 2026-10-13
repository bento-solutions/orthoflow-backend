package com.orthoflow.clinical.domain.perio;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything charted on one tooth. Sparse by design: a site with no value is simply absent,
 * so an empty chart is {@code {}} and a chart of a healthy mouth stores only what was probed.
 *
 * <ul>
 *   <li>{@code mobility}: Miller grade 0-3, one value per tooth;</li>
 *   <li>{@code furcation}: grade I-III by furcation site (see {@link PerioSites#furcationSitesOf});</li>
 *   <li>{@code pocketDepth}, {@code recession}: millimetres by probing site (recession may be negative:
 *       the gum margin sits coronal to the enamel junction);</li>
 *   <li>{@code bleeding}, {@code plaque}, {@code suppuration}: the probing sites where it was seen.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ToothMeasurements(
        Integer mobility,
        Map<String, String> furcation,
        Map<String, Integer> pocketDepth,
        Map<String, Integer> recession,
        List<String> bleeding,
        List<String> plaque,
        List<String> suppuration) {

    public static final ToothMeasurements NONE = new ToothMeasurements(null, Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of());

    public boolean isEmpty() {
        return mobility == null && isEmpty(furcation) && isEmpty(pocketDepth) && isEmpty(recession)
                && isEmpty(bleeding) && isEmpty(plaque) && isEmpty(suppuration);
    }

    private static boolean isEmpty(java.util.Collection<?> c) {
        return c == null || c.isEmpty();
    }

    private static boolean isEmpty(Map<?, ?> m) {
        return m == null || m.isEmpty();
    }

    public Map<String, Integer> depths() {
        return pocketDepth == null ? Map.of() : pocketDepth;
    }

    public Map<String, Integer> recessions() {
        return recession == null ? Map.of() : recession;
    }

    public boolean hasBleeding(String site) {
        return bleeding != null && bleeding.contains(site);
    }

    public boolean hasPlaque(String site) {
        return plaque != null && plaque.contains(site);
    }

    public boolean hasSuppuration(String site) {
        return suppuration != null && suppuration.contains(site);
    }

    /**
     * The same chart with every collection non-null, the site lists in chart order and without
     * duplicates, so a list that is stored does not depend on the order a client sent it in. (The maps
     * end up in a {@code jsonb} column, which keeps its own key order: readers must not rely on it.)
     */
    public ToothMeasurements canonical() {
        return new ToothMeasurements(
                mobility,
                ordered(furcation, PerioSites.FURCATION_SITE_ORDER),
                ordered(pocketDepth, PerioSites.PROBING_SITES),
                ordered(recession, PerioSites.PROBING_SITES),
                PerioSites.inChartOrder(bleeding == null ? List.of() : bleeding, PerioSites.PROBING_SITES),
                PerioSites.inChartOrder(plaque == null ? List.of() : plaque, PerioSites.PROBING_SITES),
                PerioSites.inChartOrder(suppuration == null ? List.of() : suppuration, PerioSites.PROBING_SITES));
    }

    private static <V> Map<String, V> ordered(Map<String, V> values, List<String> order) {
        Map<String, V> result = new LinkedHashMap<>();
        if (values != null) {
            for (String key : order) {
                V value = values.get(key);
                if (value != null) {
                    result.put(key, value);
                }
            }
        }
        return result;
    }
}
