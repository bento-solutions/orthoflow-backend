package com.orthoflow.insurance.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Beneficiary;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.FormData;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Insured;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Line;
import com.orthoflow.insurance.domain.model.InsuranceForm;
import com.orthoflow.insurance.infrastructure.forms.FormValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The words and boxes of a form, before any PDF. */
class FormValuesMapperTest {

    @Test
    void anAmountIsWrittenTheFrenchWay() {
        assertThat(FormValuesMapper.money(new BigDecimal("1250"))).isEqualTo("1 250,00");
        assertThat(FormValuesMapper.money(new BigDecimal("90.5"))).isEqualTo("90,50");
    }

    @Test
    void theNameIsLastNameFirstInCapitals() {
        assertThat(FormValuesMapper.formName("Yasmine", "Bennani")).isEqualTo("BENNANI Yasmine");
        assertThat(FormValuesMapper.formName(null, "el idrissi")).isEqualTo("EL IDRISSI");
    }

    @Test
    void theActsTickTheirTypeOfCareAndTheTeethFitTheColumn() {
        FormData d = data(List.of(new Line(LocalDate.of(2026, 10, 9), "11, 21", "D629", "ODF", "D 90", new BigDecimal("1000")),
                new Line(LocalDate.of(2026, 10, 9), "36", "D708", "Obturation", "D 12", null)));

        FormValues v = FormValuesMapper.map(d);

        assertThat(v.checks()).contains("care.ODF", "care.SOINS", "purpose.EXECUTION", "relation.CHILD", "sex.F")
                .doesNotContain("care.PROTHESE");
        assertThat(v.rows().get(0)).containsEntry("teeth", "11 21").containsEntry("date", "09/10/26")
                .containsEntry("amount", "1 000,00");
        assertThat(v.rows().get(1)).doesNotContainKey("amount");
        assertThat(v.text()).containsEntry("beneficiary.dobDigits", "14032014").containsEntry("doctor.dateDigits", "09102026")
                .containsEntry("doctor.place", "Casablanca").containsEntry("total", "1 000,00");
    }

    @Test
    void theRelationIsWrittenInWordsAndTheTreatmentSpansTheActsDates() {
        FormData d = data(List.of(new Line(LocalDate.of(2026, 10, 12), "36", "D708", "Obturation", "D 12", null),
                new Line(LocalDate.of(2026, 10, 9), "11", "D708", "Obturation", null, null)));

        FormValues v = FormValuesMapper.map(d);

        assertThat(v.text()).containsEntry("beneficiary.relation", "Enfant").containsEntry("care.startDate", "09/10/2026")
                .containsEntry("care.endDate", "12/10/2026");
        // "Coefficient de l'intervention": the cotation, or the act's own code when it has none.
        assertThat(v.rows().get(0)).containsEntry("coefficient", "D 12");
        assertThat(v.rows().get(1)).containsEntry("coefficient", "D708");
    }

    @Test
    void theProstheticPartOfAFormIsFilledOnlyForAProstheticOrPriorAgreementForm() {
        assertThat(FormValuesMapper.map(data(List.of(new Line(LocalDate.of(2026, 10, 9), "36", "D708", "Obturation", "D 12", null)))).text())
                .doesNotContainKeys("proposal.owner", "proposal.date", "proposal.total");

        FormValues crown = FormValuesMapper.map(data(List.of(new Line(LocalDate.of(2026, 10, 9), "36", "D762", "Couronne", "D 50",
                new BigDecimal("2500")))));
        assertThat(crown.text()).containsEntry("proposal.owner", "BENNANI Yasmine").containsEntry("proposal.practitioner", "Dr Amrani")
                .containsEntry("proposal.date", "09/10/2026").containsEntry("proposal.total", "1 000,00");

        FormData agreement = new FormData("CNOPS", "CNOPS", "cnops-dentaire", "CNOPS", InsuranceForm.Purpose.PRIOR_AGREEMENT,
                LocalDate.of(2026, 10, 9), new Insured("BENNANI Karim", null, null, null, null, null),
                new Beneficiary(null, null, null, null), "SELF", "Dr Amrani", null, "Cabinet", null, "Casablanca", null, null,
                List.of(new Line(null, null, "D629", "ODF", "D 90", null)), null);
        assertThat(FormValuesMapper.map(agreement).text()).containsEntry("proposal.owner", "BENNANI Karim")
                .containsEntry("beneficiary.relation", "Lui-même / elle-même").containsEntry("care.startDate", "09/10/2026");
    }

    @Test
    void theNgapNumberSaysWhichCareBoxIsTicked() {
        assertThat(CareType.of("D629")).isEqualTo(CareType.ODF);
        assertThat(CareType.of("d626")).isEqualTo(CareType.ODF);
        assertThat(CareType.of("D748")).isEqualTo(CareType.PROTHESE);
        assertThat(CareType.of("D816")).isEqualTo(CareType.PROTHESE);
        assertThat(CareType.of("D708")).isEqualTo(CareType.SOINS);
        assertThat(CareType.of("C")).isEqualTo(CareType.SOINS);
        assertThat(CareType.of("ASD03")).isEqualTo(CareType.AUTRES);
        assertThat(CareType.of(null)).isEqualTo(CareType.AUTRES);
        assertThat(CareType.needsPriorAgreement("D762", false)).isTrue();
        assertThat(CareType.needsPriorAgreement("D708", false)).isFalse();
        assertThat(CareType.needsPriorAgreement("ASD01", true)).isTrue();
    }

    private static FormData data(List<Line> lines) {
        return new FormData("CNOPS", "CNOPS", "cnops-dentaire", "CNOPS", InsuranceForm.Purpose.EXECUTION, LocalDate.of(2026, 10, 9),
                new Insured("BENNANI Karim", "BK123456", "123456789", "1234567", null, null),
                new Beneficiary("BENNANI Yasmine", null, LocalDate.of(2014, 3, 14), "f"), "CHILD", "Dr Amrani", "123456789",
                "Cabinet", null, "Casablanca", null, null, lines, new BigDecimal("1000"));
    }
}
