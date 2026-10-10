package com.orthoflow.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.orthoflow.prescription.application.dto.PrescriptionDtos.AllergyWarning;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A drug the patient's record says they react to, by name or by family. */
class AllergyCheckTest {

    private static final Line AUGMENTIN = new Line("AUGMENTIN 1 G/125 MG", "Sachet", "Amoxicilline + acide clavulanique", "1 sachet 3 fois par jour");
    private static final Line BRUFEN = new Line("BRUFEN 400 MG", "Comprimé", "Ibuprofène", "1 comprimé matin et soir");
    private static final Line ELUDRIL = new Line("ELUDRIL", "Bain de bouche", "Chlorhexidine + chlorobutanol", "matin et soir");

    @Test
    void anAmoxicillinIsFlaggedForAPatientAllergicToPenicillinWhateverTheAccents() {
        List<AllergyWarning> w = AllergyCheck.check(List.of(AUGMENTIN, ELUDRIL), List.of("Pénicilline"));

        assertThat(w).singleElement().satisfies(x -> {
            assertThat(x.drug()).isEqualTo("AUGMENTIN 1 G/125 MG");
            assertThat(x.allergy()).isEqualTo("Pénicilline");
            assertThat(x.reason()).contains("bêta-lactamines");
        });
    }

    @Test
    void aDrugNamingTheAllergenItselfIsFlagged() {
        assertThat(AllergyCheck.check(List.of(ELUDRIL), List.of("chlorhexidine"))).hasSize(1);
        assertThat(AllergyCheck.check(List.of(BRUFEN), List.of("aspirine"))).singleElement()
                .satisfies(x -> assertThat(x.reason()).contains("AINS"));
    }

    @Test
    void unrelatedAllergiesRaiseNothing() {
        assertThat(AllergyCheck.check(List.of(AUGMENTIN, BRUFEN, ELUDRIL), List.of("Latex", "Arachide", " "))).isEmpty();
    }
}
