package com.orthoflow.clinical.domain.perio;

import com.orthoflow.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerioChartValidatorTest {

    private static ToothMeasurements depths(Map<String, Integer> depths) {
        return new ToothMeasurements(null, Map.of(), depths, Map.of(), List.of(), List.of(), List.of());
    }

    private static void validate(Map<String, String> status, Map<String, ToothMeasurements> teeth) {
        PerioChartValidator.validate(status, teeth);
    }

    @Test
    void aFullyChartedToothPasses() {
        ToothMeasurements tooth = new ToothMeasurements(1, Map.of("buccal", "II", "palatal_mesial", "I"),
                Map.of("mesiobuccal", 3, "distolingual", 16), Map.of("buccal", -10, "lingual", 16),
                List.of("buccal"), List.of("lingual"), List.of("mesiobuccal"));

        assertThatCode(() -> validate(Map.of(), Map.of("16", tooth))).doesNotThrowAnyException();
    }

    @Test
    void thereAreThirtyTwoTeethAndSixSitesOnEach() {
        assertThat(PerioSites.TEETH).hasSize(32).doesNotHaveDuplicates();
        assertThat(PerioSites.PROBING_SITES).hasSize(6);
    }

    @Test
    void anUnknownToothIsRefused() {
        assertThatThrownBy(() -> validate(Map.of(), Map.of("19", depths(Map.of("buccal", 3)))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Unknown tooth: 19");
        assertThatThrownBy(() -> validate(Map.of("55", "missing"), Map.of()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Unknown tooth: 55");
    }

    @Test
    void pocketDepthStaysWithinZeroAndSixteenMillimetres() {
        assertThatCode(() -> validate(Map.of(), Map.of("11", depths(Map.of("buccal", 0))))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(Map.of(), Map.of("11", depths(Map.of("buccal", 17)))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Pocket depth of tooth 11");
        assertThatThrownBy(() -> validate(Map.of(), Map.of("11", depths(Map.of("buccal", -1)))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void recessionMayBeNegativeDownToTenMillimetres() {
        ToothMeasurements ok = new ToothMeasurements(null, Map.of(), Map.of(), Map.of("buccal", -10), List.of(), List.of(), List.of());
        ToothMeasurements tooLow = new ToothMeasurements(null, Map.of(), Map.of(), Map.of("buccal", -11), List.of(), List.of(), List.of());
        ToothMeasurements tooHigh = new ToothMeasurements(null, Map.of(), Map.of(), Map.of("buccal", 17), List.of(), List.of(), List.of());

        assertThatCode(() -> validate(Map.of(), Map.of("11", ok))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(Map.of(), Map.of("11", tooLow))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> validate(Map.of(), Map.of("11", tooHigh))).isInstanceOf(ValidationException.class);
    }

    @Test
    void mobilityIsAMillerGradeFromZeroToThree() {
        ToothMeasurements grade4 = new ToothMeasurements(4, Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> validate(Map.of(), Map.of("11", grade4)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Mobility");
    }

    @Test
    void anUnknownSiteIsRefusedWhereverItAppears() {
        ToothMeasurements badDepth = depths(Map.of("palatal", 3));
        ToothMeasurements badBleeding = new ToothMeasurements(null, Map.of(), Map.of(), Map.of(), List.of("middle"), List.of(), List.of());

        assertThatThrownBy(() -> validate(Map.of(), Map.of("11", badDepth))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> validate(Map.of(), Map.of("11", badBleeding))).isInstanceOf(ValidationException.class);
    }

    @Test
    void furcationOnlyExistsWhereATeethRootsCanForkIt() {
        ToothMeasurements onMolar = new ToothMeasurements(null, Map.of("palatal_distal", "III"), Map.of(), Map.of(), List.of(), List.of(), List.of());
        ToothMeasurements onIncisor = new ToothMeasurements(null, Map.of("buccal", "I"), Map.of(), Map.of(), List.of(), List.of(), List.of());
        ToothMeasurements onLowerMolarPalate = new ToothMeasurements(null, Map.of("palatal_mesial", "I"), Map.of(), Map.of(), List.of(), List.of(), List.of());
        ToothMeasurements badGrade = new ToothMeasurements(null, Map.of("buccal", "IV"), Map.of(), Map.of(), List.of(), List.of(), List.of());

        assertThatCode(() -> validate(Map.of(), Map.of("26", onMolar))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(Map.of(), Map.of("11", onIncisor)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("no furcation site");
        assertThatThrownBy(() -> validate(Map.of(), Map.of("46", onLowerMolarPalate))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> validate(Map.of(), Map.of("16", badGrade)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("I, II or III");
    }

    @Test
    void furcationSitesFollowTheRootsOfEachKindOfTooth() {
        assertThat(PerioSites.furcationSitesOf("16")).containsExactly("buccal", "palatal_mesial", "palatal_distal");
        assertThat(PerioSites.furcationSitesOf("24")).containsExactly("buccal");
        assertThat(PerioSites.furcationSitesOf("37")).containsExactly("buccal", "lingual");
        assertThat(PerioSites.furcationSitesOf("34")).isEmpty();
        assertThat(PerioSites.furcationSitesOf("13")).isEmpty();
    }

    @Test
    void aMissingOrPonticToothHasNothingToProbe() {
        ToothMeasurements probed = depths(Map.of("buccal", 3));

        assertThatThrownBy(() -> validate(Map.of("16", "missing"), Map.of("16", probed)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> validate(Map.of("16", "pontic"), Map.of("16", probed)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("pontic");
    }

    @Test
    void anImplantIsProbedLikeAnyOtherTooth() {
        assertThatCode(() -> validate(Map.of("16", "implant"), Map.of("16", depths(Map.of("buccal", 3)))))
                .doesNotThrowAnyException();
    }

    @Test
    void aToothStatusIsOneOfThree() {
        assertThatThrownBy(() -> validate(Map.of("16", "crowned"), Map.of()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Unknown status");
    }

    @Test
    void anEmptyToothEntryIsHarmlessEvenOnAMissingTooth() {
        assertThatCode(() -> validate(Map.of("16", "missing"), Map.of("16", ToothMeasurements.NONE)))
                .doesNotThrowAnyException();
    }

    @Test
    void canonicalFormIsInChartOrderWithoutDuplicates() {
        ToothMeasurements untidy = new ToothMeasurements(null, Map.of(),
                Map.of("distolingual", 4, "buccal", 3, "mesiobuccal", 2), Map.of(),
                List.of("distobuccal", "mesiobuccal", "distobuccal"), List.of(), List.of());

        ToothMeasurements tidy = untidy.canonical();

        assertThat(tidy.pocketDepth().keySet()).containsExactly("mesiobuccal", "buccal", "distolingual");
        assertThat(tidy.bleeding()).containsExactly("mesiobuccal", "distobuccal");
    }
}
