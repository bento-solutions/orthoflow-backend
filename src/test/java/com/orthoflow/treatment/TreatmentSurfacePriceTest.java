package com.orthoflow.treatment;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import com.orthoflow.treatment.application.dto.TreatmentRequest;
import com.orthoflow.treatment.application.dto.TreatmentSurfacePriceDto;
import com.orthoflow.treatment.application.service.TreatmentService;
import com.orthoflow.treatment.domain.model.Treatment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The clinic's tariff by number of faces, as saved (V63) and as quoted. */
class TreatmentSurfacePriceTest extends SpringDbTest {

    @Autowired
    private TreatmentService treatments;

    private static TreatmentRequest composite(List<TreatmentSurfacePriceDto> tariff) {
        TreatmentRequest r = new TreatmentRequest();
        r.setName("Composite");
        r.setCode("COMPOSITE-" + System.nanoTime());
        r.setBasePrice(new BigDecimal("300"));
        r.setSurfacePrices(tariff);
        return r;
    }

    @Test
    void theTariffIsSavedAndTheQuoteFollowsTheFacesNamed() {
        signInTo(PostgresTestSupport.newPractice(PostgresTestSupport.jdbc()));
        Treatment saved = treatments.saveTreatment(composite(List.of(
                new TreatmentSurfacePriceDto(1, new BigDecimal("250")),
                new TreatmentSurfacePriceDto(2, new BigDecimal("400")))), null);

        assertThat(treatments.priceFor(saved.getId(), "occlusal").price()).isEqualByComparingTo("250");
        assertThat(treatments.priceFor(saved.getId(), "proximal").price()).isEqualByComparingTo("400");
        assertThat(treatments.priceFor(saved.getId(), "mesial-occlusal-distal").price()).isEqualByComparingTo("400");
        assertThat(treatments.priceFor(saved.getId(), null).price()).isEqualByComparingTo("300");
    }

    @Test
    void savingWithoutATariffLeavesItAndAnEmptyListClearsIt() {
        signInTo(PostgresTestSupport.newPractice(PostgresTestSupport.jdbc()));
        Treatment saved = treatments.saveTreatment(composite(List.of(new TreatmentSurfacePriceDto(1, new BigDecimal("250")))), null);

        TreatmentRequest rename = composite(null);
        rename.setCode(saved.getCode());
        treatments.saveTreatment(rename, saved.getId());
        assertThat(treatments.priceFor(saved.getId(), "mesial").price()).isEqualByComparingTo("250");

        TreatmentRequest clear = composite(List.of());
        clear.setCode(saved.getCode());
        treatments.saveTreatment(clear, saved.getId());
        assertThat(treatments.priceFor(saved.getId(), "mesial").price()).isEqualByComparingTo("300");
    }

    @Test
    void theSameNumberOfFacesPricedTwiceIsRefused() {
        signInTo(PostgresTestSupport.newPractice(PostgresTestSupport.jdbc()));
        assertThatThrownBy(() -> treatments.saveTreatment(composite(List.of(
                new TreatmentSurfacePriceDto(2, new BigDecimal("400")),
                new TreatmentSurfacePriceDto(2, new BigDecimal("450")))), null))
                .isInstanceOf(ValidationException.class);
    }
}
