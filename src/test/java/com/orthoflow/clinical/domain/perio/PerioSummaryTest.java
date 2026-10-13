package com.orthoflow.clinical.domain.perio;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The figures are worked out by hand below. The web client has a spec with the very same chart and
 * the very same expectations: if either implementation drifts, one of the two fails.
 */
class PerioSummaryTest {

    /** Six sites examined across three teeth, five depths, two recessions. */
    static Map<String, ToothMeasurements> sampleChart() {
        Map<String, ToothMeasurements> teeth = new LinkedHashMap<>();
        teeth.put("16", new ToothMeasurements(null, Map.of(),
                Map.of("mesiobuccal", 3, "buccal", 4, "distobuccal", 6),
                Map.of("mesiobuccal", 1, "buccal", 0),
                List.of("buccal", "distobuccal"), List.of("mesiobuccal"), List.of()));
        teeth.put("26", new ToothMeasurements(null, Map.of(),
                Map.of("mesiobuccal", 2, "buccal", 10), Map.of(),
                List.of(), List.of(), List.of("buccal")));
        teeth.put("11", new ToothMeasurements(null, Map.of(), Map.of(), Map.of(),
                List.of("buccal"), List.of(), List.of()));
        return teeth;
    }

    @Test
    void anEmptyChartHasNothingToReport() {
        PerioSummary summary = PerioSummary.of(Map.of());

        assertThat(summary.sitesRecorded()).isZero();
        assertThat(summary.bleedingPercent()).isZero();
        assertThat(summary.meanPocketDepth()).isNull();
        assertThat(summary.pocketsAboveFourPercent()).isNull();
        assertThat(summary.deepestPocketDepth()).isNull();
        assertThat(summary.meanCal()).isNull();
    }

    @Test
    void percentagesAreOfTheSitesThatWereExamined() {
        PerioSummary summary = PerioSummary.of(sampleChart());

        assertThat(summary.sitesRecorded()).isEqualTo(6);
        assertThat(summary.bleedingPercent()).isEqualTo(50.0);      // 3 of 6
        assertThat(summary.plaquePercent()).isEqualTo(16.7);        // 1 of 6
        assertThat(summary.suppurationPercent()).isEqualTo(16.7);   // 1 of 6
    }

    @Test
    void pocketFiguresAreOfTheRecordedDepthsOnly() {
        PerioSummary summary = PerioSummary.of(sampleChart());

        assertThat(summary.meanPocketDepth()).isEqualTo(5.0);               // (3+4+6+2+10) / 5
        assertThat(summary.pocketsAboveFourPercent()).isEqualTo(60.0);      // 4, 6, 10 of 5 depths
        assertThat(summary.deepestPocketDepth()).isEqualTo(10);
    }

    @Test
    void attachmentLossNeedsBothADepthAndARecessionOnTheSameSite() {
        PerioSummary summary = PerioSummary.of(sampleChart());

        assertThat(summary.meanRecession()).isEqualTo(0.5);                 // (1+0) / 2
        assertThat(summary.meanCal()).isEqualTo(4.0);                       // (3+1, 4+0) / 2
        assertThat(summary.sitesAboveFiveCalPercent()).isEqualTo(0.0);
    }

    @Test
    void aZeroDepthIsARecordedDepthNotAMissingOne() {
        Map<String, ToothMeasurements> teeth = Map.of("11", new ToothMeasurements(null, Map.of(),
                Map.of("buccal", 0), Map.of(), List.of(), List.of(), List.of()));

        PerioSummary summary = PerioSummary.of(teeth);

        assertThat(summary.sitesRecorded()).isEqualTo(1);
        assertThat(summary.meanPocketDepth()).isEqualTo(0.0);
        assertThat(summary.pocketsAboveFourPercent()).isEqualTo(0.0);
        assertThat(summary.deepestPocketDepth()).isZero();
    }

    @Test
    void aNegativeRecessionLowersTheMean() {
        Map<String, ToothMeasurements> teeth = Map.of("11", new ToothMeasurements(null, Map.of(),
                Map.of("buccal", 5), Map.of("buccal", -2), List.of(), List.of(), List.of()));

        PerioSummary summary = PerioSummary.of(teeth);

        assertThat(summary.meanRecession()).isEqualTo(-2.0);
        assertThat(summary.meanCal()).isEqualTo(3.0);
    }

    @Test
    void mobilityAndFurcationAloneAreNotSites() {
        Map<String, ToothMeasurements> teeth = Map.of("16", new ToothMeasurements(2, Map.of("buccal", "II"),
                Map.of(), Map.of(), List.of(), List.of(), List.of()));

        assertThat(PerioSummary.of(teeth).sitesRecorded()).isZero();
    }
}
