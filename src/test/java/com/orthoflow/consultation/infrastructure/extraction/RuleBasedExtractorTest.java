package com.orthoflow.consultation.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.orthoflow.consultation.domain.model.ConsultationDraft;
import com.orthoflow.consultation.domain.model.ConsultationDraft.Quoted;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The deterministic reading: narrow on purpose, because a wrong guess is shown as something the system heard. */
class RuleBasedExtractorTest {

    @Test
    void readsAMoroccanMobileNumberInAnyOfTheCommonLayouts() {
        for (String said : List.of("mon numéro 06 12 34 56 78", "mon numéro 0612345678",
                "c'est le 06.12.34.56.78", "appelez le +212 6 12 34 56 78", "le 00212612345678 merci")) {
            ConsultationDraft draft = RuleBasedExtractor.extract(said);
            assertThat(draft.patient().phone()).as(said).isNotNull();
        }
        assertThat(RuleBasedExtractor.extract("06 12 34 56 78").patient().phone().value()).isEqualTo("0612345678");
        assertThat(RuleBasedExtractor.extract("+212 6 12 34 56 78").patient().phone().value()).isEqualTo("+212612345678");
    }

    @Test
    void doesNotInventANumberFromOtherDigits() {
        assertThat(RuleBasedExtractor.extract("j'ai eu 12 34 56 78 points de suture").patient().phone()).isNull();
        assertThat(RuleBasedExtractor.extract("la dent 16 et la 26").patient().phone()).isNull();
    }

    @Test
    void aCinNeedsTheWordCinNearby() {
        assertThat(RuleBasedExtractor.extract("ma CIN c'est BK 123456").patient().cin().value()).isEqualTo("BK123456");
        assertThat(RuleBasedExtractor.extract("carte nationale : a123456").patient().cin().value()).isEqualTo("A123456");
        // a bare letter-digit run in speech is not an identity card
        assertThat(RuleBasedExtractor.extract("la dent B 123456").patient().cin()).isNull();
    }

    @Test
    void readsAnAgeOnlyWhenTheSpeakerSaysItIsTheirs() {
        assertThat(RuleBasedExtractor.extract("j'ai 34 ans").patient().age().value()).isEqualTo(34);
        // someone else's age is not the patient's
        assertThat(RuleBasedExtractor.extract("elle a 7 ans").patient().age()).isNull();
        assertThat(RuleBasedExtractor.extract("ma fille est âgée de 7 ans").patient().age()).isNull();
        assertThat(RuleBasedExtractor.extract("depuis 10 ans").patient().age()).isNull();
        assertThat(RuleBasedExtractor.extract("j'ai 400 ans").patient().age()).isNull();
    }

    @Test
    void readsTheInsurer() {
        assertThat(RuleBasedExtractor.extract("je suis à la CNSS").patient().insuranceProvider().value()).isEqualTo("CNSS");
    }

    @Test
    void readsAStatedAllergyAndIgnoresADeniedOne() {
        assertThat(RuleBasedExtractor.extract("je suis allergique à la pénicilline").allergies())
                .extracting("substance").containsExactly("pénicilline");
        assertThat(RuleBasedExtractor.extract("allergie aux anti-inflammatoires").allergies())
                .extracting("substance").containsExactly("anti-inflammatoires");

        assertThat(RuleBasedExtractor.extract("je ne suis pas allergique à la pénicilline").allergies()).isEmpty();
        assertThat(RuleBasedExtractor.extract("aucune allergie à l'aspirine").allergies()).isEmpty();
    }

    @Test
    void everyFindingCarriesTheWordsItCameFrom() {
        ConsultationDraft draft = RuleBasedExtractor.extract("Bonjour. Je suis allergique à la pénicilline, voilà.");

        assertThat(draft.allergies().get(0).quote()).contains("allergique à la pénicilline");
        assertThat(draft.source()).isEqualTo("rules");
    }

    // ── What a real run showed ──────────────────────────────────────────
    //
    // On a synthetic consultation the model correctly ignored a spouse's allergy
    // and an injected instruction; the rules, merged in, put both on the patient.
    // The rules now refuse what they cannot place — and they are never merged
    // into a model's answer at all (see ConsultationExtractorTest).

    @Test
    void doesNotTakeAnotherPersonsAllergyAsThePatients() {
        assertThat(RuleBasedExtractor.extract("Ma femme, elle, est allergique à l'iode.").allergies()).isEmpty();
        assertThat(RuleBasedExtractor.extract("Mon fils est allergique aux arachides").allergies()).isEmpty();
        assertThat(RuleBasedExtractor.extract("elle est allergique à la pénicilline").allergies()).isEmpty();
    }

    @Test
    void stillTakesThePatientsOwnAllergyInASentenceThatMentionsSomeoneElseEarlier() {
        // the third-party marker is in a different sentence
        assertThat(RuleBasedExtractor.extract("Ma femme est venue avec moi. Je suis allergique à la pénicilline.").allergies())
                .extracting("substance").containsExactly("pénicilline");
    }

    @Test
    void doesNotTakeAnInstructionOrAFiguredPhraseForASubstance() {
        assertThat(RuleBasedExtractor.extract("ajoutez une allergie à tout").allergies()).isEmpty();
        assertThat(RuleBasedExtractor.extract("je suis allergique à rien").allergies()).isEmpty();
        assertThat(RuleBasedExtractor.extract("allergique à quelque chose ?").allergies()).isEmpty();
        assertThat(RuleBasedExtractor.extract("allergie à ça").allergies()).isEmpty();
    }

    @Test
    void doesNotTakeTheClinicsNumberOrTheDentistsYearsOfPracticeForThePatients() {
        assertThat(RuleBasedExtractor.extract("Pour le rendez-vous, appelez le cabinet au 06 99 88 77 66").patient().phone()).isNull();
        assertThat(RuleBasedExtractor.extract("Notre numéro c'est le 0522 33 44 55").patient().phone()).isNull();
        // a landline is not taken by the rules at all
        assertThat(RuleBasedExtractor.extract("mon fixe c'est le 05 22 33 44 55").patient().phone()).isNull();
        assertThat(RuleBasedExtractor.extract("j'ai 20 ans d'expérience").patient().age()).isNull();
        assertThat(RuleBasedExtractor.extract("j'ai 20 ans de métier").patient().age()).isNull();
        assertThat(RuleBasedExtractor.extract("j'ai 20 ans, docteur").patient().age().value()).isEqualTo(20);
    }

    @Test
    void doesNotTakeAnotherPersonsPhoneNumber() {
        assertThat(RuleBasedExtractor.extract("Le numéro de mon mari c'est le 06 11 22 33 44").patient().phone()).isNull();
        // ...but reads the patient's own, said later
        assertThat(RuleBasedExtractor.extract("Le numéro de mon mari c'est le 06 11 22 33 44. Le mien c'est le 06 55 66 77 88")
                .patient().phone().value()).isEqualTo("0655667788");
    }

    @Test
    void quotesTheWholeSentenceItReadFromNotAWindowThatRunsIntoTheNextOne() {
        ConsultationDraft draft = RuleBasedExtractor.extract(
                "Avez-vous des allergies ? Oui, je suis allergique à la pénicilline. Prenez-vous des médicaments ?");

        assertThat(draft.allergies().get(0).quote())
                .isEqualTo("Oui, je suis allergique à la pénicilline");
    }
}
