package com.orthoflow.clinical.domain.perio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What can be charted on which tooth. A chart is a fixed grid (32 teeth x 6 probing sites),
 * so everything a client may send is checked against these lists and nothing else is stored.
 */
public final class PerioSites {

    private PerioSites() {
    }

    /** FDI codes, quadrant by quadrant, in the order the chart reads them left to right. */
    public static final List<String> TEETH = List.of(
            "18", "17", "16", "15", "14", "13", "12", "11", "21", "22", "23", "24", "25", "26", "27", "28",
            "48", "47", "46", "45", "44", "43", "42", "41", "31", "32", "33", "34", "35", "36", "37", "38");

    /** The six probing sites of a tooth: three on the cheek side, three on the tongue/palate side. */
    public static final List<String> PROBING_SITES = List.of(
            "mesiobuccal", "buccal", "distobuccal", "mesiolingual", "lingual", "distolingual");

    /** The tooth is absent (nothing to probe), replaced by a bridge pontic (nothing to probe) or an implant (probed). */
    public static final String MISSING = "missing";
    public static final String PONTIC = "pontic";
    public static final String IMPLANT = "implant";
    public static final Set<String> TOOTH_STATUSES = Set.of(MISSING, PONTIC, IMPLANT);

    /** Hamp classification of a furcation involvement. */
    public static final Set<String> FURCATION_GRADES = Set.of("I", "II", "III");

    public static final int MAX_POCKET_DEPTH = 16;
    public static final int MIN_RECESSION = -10;
    public static final int MAX_RECESSION = 16;
    public static final int MAX_MOBILITY = 3;

    /** Every furcation site there is, in chart order. */
    public static final List<String> FURCATION_SITE_ORDER = List.of("buccal", "lingual", "palatal_mesial", "palatal_distal");

    private static final List<String> MAXILLARY_MOLAR_FURCATIONS = List.of("buccal", "palatal_mesial", "palatal_distal");
    private static final List<String> MAXILLARY_PREMOLAR_FURCATIONS = List.of("buccal");
    private static final List<String> MANDIBULAR_MOLAR_FURCATIONS = List.of("buccal", "lingual");

    /**
     * Where a furcation can be probed. Maxillary molars have three roots (cheek, and the
     * mesial and distal sides of the palate), mandibular molars two (cheek and tongue), upper
     * premolars one. Lower premolars and all front teeth have none.
     */
    private static final Map<String, List<String>> FURCATION_SITES = furcationSites();

    private static Map<String, List<String>> furcationSites() {
        Map<String, List<String>> sites = new java.util.LinkedHashMap<>();
        for (String tooth : List.of("16", "17", "18", "26", "27", "28")) {
            sites.put(tooth, MAXILLARY_MOLAR_FURCATIONS);
        }
        for (String tooth : List.of("14", "15", "24", "25")) {
            sites.put(tooth, MAXILLARY_PREMOLAR_FURCATIONS);
        }
        for (String tooth : List.of("36", "37", "38", "46", "47", "48")) {
            sites.put(tooth, MANDIBULAR_MOLAR_FURCATIONS);
        }
        return Map.copyOf(sites);
    }

    public static List<String> furcationSitesOf(String tooth) {
        return FURCATION_SITES.getOrDefault(tooth, List.of());
    }

    public static boolean isTooth(String code) {
        return code != null && TEETH.contains(code);
    }

    /** Sites in chart order, for a stable stored representation. */
    public static List<String> inChartOrder(java.util.Collection<String> sites, List<String> order) {
        List<String> ordered = new ArrayList<>();
        for (String site : order) {
            if (sites.contains(site)) {
                ordered.add(site);
            }
        }
        return ordered;
    }
}
