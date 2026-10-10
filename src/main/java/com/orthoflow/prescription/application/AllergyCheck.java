package com.orthoflow.prescription.application;

import com.orthoflow.prescription.application.dto.PrescriptionDtos.AllergyWarning;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.Line;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compares the drugs of an ordonnance with the allergies on the patient's record. A drug
 * is flagged when its name or active substance names the allergen, or when both belong to
 * a family that cross-reacts (an amoxicillin prescription for a patient allergic to
 * penicillin). It warns; it does not decide. It knows only the families listed here, so
 * no warning is not a clearance, and the screen says so.
 */
public final class AllergyCheck {

    /** A drug family: the words an allergy is written with, and the substances that belong to it. */
    private record Family(String name, List<String> allergyWords, List<String> substances) {
    }

    private static final List<Family> FAMILIES = List.of(
            new Family("bêta-lactamines (pénicillines)",
                    List.of("penicilline", "penicillin", "amoxicilline", "amoxicillin", "ampicilline", "beta-lactam",
                            "betalactam", "beta lactam", "augmentin", "clamoxyl", "cephalosporine", "cephalosporin"),
                    List.of("amoxicilline", "ampicilline", "penicilline", "oxacilline", "cloxacilline", "clavulan", "amoxiclav")),
            new Family("anti-inflammatoires non stéroïdiens (AINS)",
                    List.of("ains", "nsaid", "anti-inflammatoire", "aspirine", "aspirin", "ibuprofene", "ibuprofen",
                            "diclofenac", "ketoprofene", "naproxene", "brufen", "voltarene"),
                    List.of("ibuprofene", "diclofenac", "ketoprofene", "naproxene", "acide acetylsalicylique", "aspirine")),
            new Family("paracétamol", List.of("paracetamol", "acetaminophen", "doliprane", "efferalgan"), List.of("paracetamol")),
            new Family("opioïdes (codéine)", List.of("codeine", "morphine", "opioide", "opiace", "tramadol"),
                    List.of("codeine", "tramadol", "morphine")),
            new Family("nitro-imidazolés (métronidazole)", List.of("metronidazole", "flagyl", "nitro-imidazole", "nitroimidazole"),
                    List.of("metronidazole", "ornidazole", "tinidazole")),
            new Family("chlorhexidine", List.of("chlorhexidine", "eludril"), List.of("chlorhexidine")),
            new Family("streptogramines et macrolides", List.of("pristinamycine", "pyostacine", "macrolide", "erythromycine",
                    "azithromycine", "clarithromycine", "spiramycine"),
                    List.of("pristinamycine", "erythromycine", "azithromycine", "clarithromycine", "spiramycine")),
            new Family("antiviraux (aciclovir)", List.of("aciclovir", "valaciclovir", "zovirax"), List.of("aciclovir", "valaciclovir")),
            new Family("produits de la ruche (propolis)", List.of("propolis", "abeille", "miel", "pollen"), List.of("propolis")));

    private AllergyCheck() {
    }

    public static List<AllergyWarning> check(List<Line> lines, List<String> allergies) {
        List<AllergyWarning> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Line line : lines) {
            String drug = plain(line.drug() + " " + (line.dci() == null ? "" : line.dci()));
            for (String allergy : allergies) {
                if (allergy == null || allergy.isBlank()) continue;
                String a = plain(allergy);
                String reason = null;
                if (a.length() >= 4 && drug.contains(a)) {
                    reason = "le médicament contient la substance notée en allergie";
                } else {
                    for (Family f : FAMILIES) {
                        if (f.allergyWords().stream().anyMatch(a::contains) && f.substances().stream().anyMatch(drug::contains)) {
                            reason = "même famille : " + f.name();
                            break;
                        }
                    }
                }
                if (reason != null && seen.add(line.drug() + "|" + allergy)) {
                    out.add(new AllergyWarning(line.drug(), allergy.trim(), reason));
                }
            }
        }
        return out;
    }

    /** Lower case, accents off: "Pénicilline" and "penicilline" are the same allergy. */
    static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
