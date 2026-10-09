package com.orthoflow.treatment;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import com.orthoflow.treatment.application.dto.TreatmentRequest;
import com.orthoflow.treatment.application.port.TreatmentActLookup;
import com.orthoflow.treatment.application.service.NgapNomenclature;
import com.orthoflow.treatment.application.service.TreatmentService;
import com.orthoflow.treatment.domain.model.Treatment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The NGAP as transcribed (V57) and how a clinic's catalogue uses it. The figures checked
 * here are read off the published text (arrêté n° 177-06), not computed.
 */
class NgapNomenclatureTest extends SpringDbTest {

    @Autowired
    private NgapNomenclature nomenclature;
    @Autowired
    private TreatmentService treatments;
    @Autowired
    private TreatmentActLookup lookup;

    @Test
    void theDentalChaptersAreTranscribedWholeWithTheTextsTwoColumns() {
        assertThat(nomenclature.search(null, null, 300)).hasSize(207);
        assertThat(nomenclature.chapters()).hasSize(6);

        var filling = nomenclature.find("D702").orElseThrow();
        assertThat(filling.keyLetter()).isEqualTo("D");
        assertThat(filling.coefficient()).isEqualByComparingTo("15");
        assertThat(filling.cotation(null)).isEqualTo("D 15");

        var canine = nomenclature.find("D726").orElseThrow();
        assertThat(canine.coefficient()).isEqualByComparingTo("50");
        assertThat(canine.anesthesiaCoefficient()).isEqualByComparingTo("30");

        // Only the anaesthesia column is printed for these: no act coefficient is invented.
        var anaesthesia = nomenclature.find("D722").orElseThrow();
        assertThat(anaesthesia.coefficient()).isNull();
        assertThat(anaesthesia.anesthesiaCoefficient()).isEqualByComparingTo("25");
        assertThat(anaesthesia.cotation(null)).isNull();

        var orthodontics = nomenclature.find("D629").orElseThrow();
        assertThat(orthodontics.coefficient()).isEqualByComparingTo("90");
        assertThat(orthodontics.priorAgreement()).isTrue();
        assertThat(nomenclature.find("D630").orElseThrow().coefficient()).isEqualByComparingTo("540");

        assertThat(nomenclature.find("ASD01").orElseThrow().assimilatedTo()).isEqualTo("D510");
    }

    @Test
    void searchingIgnoresCaseAndAccentsAndMatchesCodePrefixes() {
        assertThat(nomenclature.search("detartrage", null, 10)).extracting(NgapNomenclature.NgapAct::code).containsExactly("D708");
        assertThat(nomenclature.search("d70", null, 20)).extracting(NgapNomenclature.NgapAct::code)
                .containsExactly("D700", "D701", "D702", "D703", "D704", "D705", "D706", "D707", "D708", "D709");
        assertThat(nomenclature.search("100%", null, 10)).isEmpty();
    }

    @Test
    void aTreatmentIsCodedAgainstTheNomenclatureAndPrintsItsCotation() {
        signInTo(PostgresTestSupport.newPractice(PostgresTestSupport.jdbc()));

        Treatment scaling = treatments.saveTreatment(request("SCALING", "d708", null), null);
        assertThat(scaling.getActCode()).isEqualTo("D708");
        assertThat(scaling.getActCoefficient()).isEqualByComparingTo("12");

        // A child's permanent molar: the text's 50 % increase is the clinic's to apply.
        Treatment childRoot = treatments.saveTreatment(request("ROOT-CHILD", "D706", new BigDecimal("37.5")), null);
        assertThat(childRoot.getActCoefficient()).isEqualByComparingTo("37.5");

        assertThat(lookup.byTreatmentCodes(List.of("SCALING", "ROOT-CHILD")))
                .extractingFromEntries(e -> e.getValue().cotation()).containsExactlyInAnyOrder("D 12", "D 37.5");
    }

    @Test
    void aCodeThatIsNotInTheNomenclatureIsRefused() {
        signInTo(PostgresTestSupport.newPractice(PostgresTestSupport.jdbc()));
        assertThatThrownBy(() -> treatments.saveTreatment(request("X", "TO 90", null), null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("TO 90");
    }

    private static TreatmentRequest request(String code, String actCode, BigDecimal coefficient) {
        TreatmentRequest r = new TreatmentRequest();
        r.setName(code);
        r.setCode(code);
        r.setBasePrice(new BigDecimal("300"));
        r.setActCode(actCode);
        r.setActCoefficient(coefficient);
        return r;
    }
}
