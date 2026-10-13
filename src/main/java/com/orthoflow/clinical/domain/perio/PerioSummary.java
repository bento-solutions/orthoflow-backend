package com.orthoflow.clinical.domain.perio;

import java.util.Map;

/**
 * The figures a periodontist reads off a chart: how many of the sites that were examined bled,
 * carried plaque or suppurated, and how deep the pockets are. A site counts as examined as soon
 * as anything was recorded on it, which is also the denominator of the percentages.
 *
 * <p>The web client computes the same figures as the clinician charts, so that they update on
 * every keystroke; the two implementations share their fixtures to stay identical.
 *
 * @param meanPocketDepth          mm, one decimal; null when no depth was recorded
 * @param pocketsAboveFourPercent  share of the recorded depths that are 4 mm or more (the usual
 *                                 threshold between a shallow and a pathological pocket)
 * @param meanCal                  mean clinical attachment level (depth + recession), over the
 *                                 sites that have both
 * @param sitesAboveFiveCalPercent share of those sites with 5 mm or more of attachment loss
 */
public record PerioSummary(
        int sitesRecorded,
        double bleedingPercent,
        double plaquePercent,
        double suppurationPercent,
        Double meanPocketDepth,
        Double pocketsAboveFourPercent,
        Integer deepestPocketDepth,
        Double meanRecession,
        Double meanCal,
        Double sitesAboveFiveCalPercent) {

    public static final PerioSummary EMPTY = of(Map.of());

    public static PerioSummary of(Map<String, ToothMeasurements> teeth) {
        int recorded = 0;
        int bleeding = 0;
        int plaque = 0;
        int suppuration = 0;
        int depthCount = 0;
        int depthSum = 0;
        int depthFourOrMore = 0;
        Integer deepest = null;
        int recessionCount = 0;
        int recessionSum = 0;
        int calCount = 0;
        int calSum = 0;
        int calFiveOrMore = 0;

        for (ToothMeasurements tooth : teeth.values()) {
            for (String site : PerioSites.PROBING_SITES) {
                Integer depth = tooth.depths().get(site);
                Integer recession = tooth.recessions().get(site);
                boolean bled = tooth.hasBleeding(site);
                boolean plaqued = tooth.hasPlaque(site);
                boolean pus = tooth.hasSuppuration(site);
                if (depth == null && recession == null && !bled && !plaqued && !pus) {
                    continue;
                }
                recorded++;
                if (bled) bleeding++;
                if (plaqued) plaque++;
                if (pus) suppuration++;
                if (depth != null) {
                    depthCount++;
                    depthSum += depth;
                    if (depth >= 4) depthFourOrMore++;
                    if (deepest == null || depth > deepest) deepest = depth;
                }
                if (recession != null) {
                    recessionCount++;
                    recessionSum += recession;
                }
                if (depth != null && recession != null) {
                    int cal = depth + recession;
                    calCount++;
                    calSum += cal;
                    if (cal >= 5) calFiveOrMore++;
                }
            }
        }

        return new PerioSummary(
                recorded,
                percent(bleeding, recorded),
                percent(plaque, recorded),
                percent(suppuration, recorded),
                depthCount == 0 ? null : round1((double) depthSum / depthCount),
                depthCount == 0 ? null : round1(100.0 * depthFourOrMore / depthCount),
                deepest,
                recessionCount == 0 ? null : round1((double) recessionSum / recessionCount),
                calCount == 0 ? null : round1((double) calSum / calCount),
                calCount == 0 ? null : round1(100.0 * calFiveOrMore / calCount));
    }

    private static double percent(int part, int whole) {
        return whole == 0 ? 0.0 : round1(100.0 * part / whole);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
