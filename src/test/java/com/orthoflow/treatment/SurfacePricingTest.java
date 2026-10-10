package com.orthoflow.treatment;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.treatment.domain.model.SurfacePricing;
import com.orthoflow.treatment.domain.model.TreatmentSurfacePrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The surface taxonomy and the price it leads to: one lesion, one set of faces, one price. */
class SurfacePricingTest {

    private static final BigDecimal BASE = new BigDecimal("300");

    private static TreatmentSurfacePrice rule(int faces, String price) {
        return TreatmentSurfacePrice.builder().surfaceCount(faces).price(new BigDecimal(price)).build();
    }

    private final List<TreatmentSurfacePrice> tariff = List.of(rule(1, "250"), rule(2, "400"), rule(3, "550"));

    @Test
    void occlusalAndIncisalAreTheSameFace() {
        assertThat(SurfacePricing.faces("occlusal")).isEqualTo(SurfacePricing.faces("incisal"));
        assertThat(SurfacePricing.faceCount("mesial-occlusal")).isEqualTo(2);
    }

    @Test
    void proximalIsMesialPlusDistalNotAThirdFace() {
        assertThat(SurfacePricing.faces("proximal")).containsExactly("mesial", "distal");
        // Said both ways it is still the same two faces, priced once.
        assertThat(SurfacePricing.faceCount("mesial-proximal")).isEqualTo(2);
        assertThat(SurfacePricing.faceCount("proximal-occlusal")).isEqualTo(3);
    }

    @Test
    void cervicalIsOneFaceOfItsOwn() {
        assertThat(SurfacePricing.faceCount("cervical")).isEqualTo(1);
        assertThat(SurfacePricing.faceCount("buccal-cervical")).isEqualTo(2);
    }

    @Test
    void anUnknownSurfaceIsRefusedRatherThanPricedAsNothing() {
        assertThatThrownBy(() -> SurfacePricing.faces("mesial-bogus")).isInstanceOf(ValidationException.class);
    }

    @Test
    void priceFollowsTheNumberOfFaces() {
        assertThat(SurfacePricing.quote(BASE, tariff, "occlusal").price()).isEqualByComparingTo("250");
        assertThat(SurfacePricing.quote(BASE, tariff, "mesial-occlusal").price()).isEqualByComparingTo("400");
        assertThat(SurfacePricing.quote(BASE, tariff, "proximal-occlusal").price()).isEqualByComparingTo("550");
        assertThat(SurfacePricing.quote(BASE, tariff, "mesial-occlusal").basis()).isEqualTo("FACES_2");
    }

    @Test
    void moreFacesThanTheTariffCoversAreChargedTheHighestRuleNeverLess() {
        assertThat(SurfacePricing.quote(BASE, tariff, "mesial-distal-buccal-occlusal").price()).isEqualByComparingTo("550");
    }

    @Test
    void noSurfaceOrNoRuleMeansTheBasePrice() {
        assertThat(SurfacePricing.quote(BASE, tariff, null).price()).isEqualByComparingTo("300");
        assertThat(SurfacePricing.quote(BASE, tariff, "").basis()).isEqualTo("BASE");
        assertThat(SurfacePricing.quote(BASE, List.of(), "mesial").price()).isEqualByComparingTo("300");
        // A tariff that starts at two faces says nothing about one.
        assertThat(SurfacePricing.quote(BASE, List.of(rule(2, "400")), "mesial").price()).isEqualByComparingTo("300");
    }
}
