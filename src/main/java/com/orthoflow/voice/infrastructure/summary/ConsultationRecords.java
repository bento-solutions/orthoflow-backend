package com.orthoflow.voice.infrastructure.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dictated examination rendered as plain clinical lines, in the dentist's
 * language, straight from the resolved audit entities.
 *
 * <p>Three jobs, all of which need the same deterministic reading:
 *
 * <ul>
 *   <li><b>The model's input.</b> It gets "Dent 16 : carie récurrente (face
 *       occlusale)" rather than {@code {"code":"recurrent_caries"}}. A model
 *       asked to turn snake_case codes into French clinical terms translates,
 *       and a translated finding is a different finding.</li>
 *   <li><b>The check on the model's output.</b> Every tooth the summary names
 *       must be one that was recorded, and every tooth with a finding must
 *       appear — see {@link #unverifiedTeeth} and {@link #missingTeeth}.</li>
 *   <li><b>The fallback.</b> When no model is configured, reachable or
 *       trustworthy, {@link #narrative} is the summary: less fluent, never
 *       wrong.</li>
 * </ul>
 */
public final class ConsultationRecords {

    /** One resolved clinical write. */
    public record Entry(String kind, String fdi, String line) {}

    private static final Map<String, String[]> FINDINGS = new LinkedHashMap<>();
    private static final Map<String, String[]> SURFACES = Map.of(
            "occlusal", new String[] {"face occlusale", "occlusal surface"},
            "mesial", new String[] {"face mésiale", "mesial surface"},
            "distal", new String[] {"face distale", "distal surface"},
            "buccal", new String[] {"face vestibulaire", "buccal surface"},
            "lingual", new String[] {"face linguale", "lingual surface"},
            "incisal", new String[] {"bord incisif", "incisal edge"},
            "cervical", new String[] {"collet", "cervical"});
    private static final Map<String, String[]> SEVERITIES = Map.of(
            "MILD", new String[] {"légère", "mild"},
            "MODERATE", new String[] {"modérée", "moderate"},
            "SEVERE", new String[] {"sévère", "severe"});

    /** Whether a finding code has a written name in both languages, rather than its code as a fallback. */
    static boolean hasLabel(String code) {
        return FINDINGS.containsKey(code);
    }

    private static void f(String code, String fr, String en) {
        FINDINGS.put(code, new String[] {fr, en});
    }

    static {
        f("existing_crown", "couronne existante", "existing crown");
        f("existing_bridge", "bridge existant", "existing bridge");
        f("existing_implant", "implant existant", "existing implant");
        f("existing_veneer", "facette existante", "existing veneer");
        f("existing_root_canal", "dent dévitalisée (traitement canalaire existant)", "previous root canal");
        f("existing_filling", "obturation existante", "existing filling");
        f("existing_composite", "composite existant", "existing composite");
        f("existing_amalgam", "amalgame existant", "existing amalgam");
        f("existing_post", "inlay core / tenon", "post and core");
        f("existing_sealant", "scellement de sillons", "existing sealant");
        f("existing_deciduous", "dent temporaire (déciduale)", "deciduous tooth");
        f("existing_inlay", "inlay", "inlay");
        f("extracted", "extraite", "extracted");
        f("missing", "absente", "missing");
        f("impacted", "incluse", "impacted");
        f("fracture", "fracture", "fracture");
        f("caries", "carie", "caries");
        f("recurrent_caries", "carie récurrente", "recurrent caries");
        f("deep_caries", "carie profonde", "deep caries");
        f("cavity", "cavité", "cavity");
        f("retained_root", "racine résiduelle", "retained root");
        f("infection", "infection", "infection");
        f("abscess", "abcès", "abscess");
        f("mobility", "mobilité", "mobility");
        f("sensitivity", "sensibilité", "sensitivity");
        f("pain", "douleur", "pain");
        f("tooth_wear", "usure", "tooth wear");
        f("discoloration", "dyschromie", "discoloration");
        f("gingival_inflammation", "inflammation gingivale", "gingival inflammation");
        f("periodontal_pocket", "poche parodontale", "periodontal pocket");
        f("gingival_recession", "récession gingivale", "gingival recession");
        f("plaque_calculus", "plaque / tartre", "plaque / calculus");
        f("malposition", "malposition", "malposition");
        f("crown_defective", "couronne défectueuse", "defective crown");
        f("avulsion", "avulsion", "avulsion");
        f("extensive_destruction", "dent délabrée", "extensively broken-down tooth");
        f("pulpitis", "pulpite", "pulpitis");
        f("necrosis", "nécrose pulpaire", "pulp necrosis");
        f("periapical_lesion", "lésion périapicale", "periapical lesion");
        f("bleeding", "saignement", "bleeding");
        f("swelling", "gonflement", "swelling");
        f("crown_replacement_required", "couronne à remplacer", "crown replacement required");
        f("crown_required", "couronne à poser", "crown required");
        f("filling_required", "obturation à faire", "filling required");
        f("extraction_required", "extraction à prévoir", "extraction required");
        f("root_canal_required", "traitement canalaire à faire", "root canal required");
        f("implant_required", "implant à poser", "implant required");
        f("bridge_required", "bridge à poser", "bridge required");
        f("veneer_required", "facette à poser", "veneer required");
        f("scaling_required", "détartrage à faire", "scaling required");
        f("sealant_required", "scellement de sillons à faire", "sealant required");
        f("restoration_required", "dent à soigner", "restoration required");
        f("periodontal_treatment_required", "traitement parodontal à faire", "periodontal treatment required");
        f("normal", "rien à signaler", "normal");
        f("monitor", "à surveiller", "monitor");
        f("follow_up", "contrôle", "follow-up");
        f("note", "note", "note");

        // The clinical referential.
        f("agenesis", "agénésie dentaire", "agenesis");
        f("partially_erupted", "partiellement éruptée", "partially erupted");
        f("unerupted", "non éruptée", "unerupted");
        f("erupting", "en cours d’éruption", "erupting");
        f("persistent_deciduous", "dent temporaire persistante", "persistent deciduous tooth");
        f("supernumerary", "dent surnuméraire", "supernumerary tooth");
        f("permanent_tooth", "dent permanente", "permanent tooth");
        f("ectopic_eruption", "éruption ectopique", "ectopic eruption");
        f("white_spot_lesion", "lésion amélaire initiale / tache blanche", "white spot lesion");
        f("demineralisation", "déminéralisation", "demineralisation");
        f("cracked_tooth", "dent fissurée", "cracked tooth");
        f("craze_lines", "fêlures amélaires (craze lines)", "enamel craze lines");
        f("cusp_fracture", "fracture cuspidienne", "cusp fracture");
        f("enamel_dentin_fracture", "fracture amélo-dentinaire", "enamel-dentin fracture");
        f("complicated_crown_fracture", "fracture coronaire compliquée", "complicated crown fracture");
        f("crown_root_fracture", "fracture corono-radiculaire", "crown-root fracture");
        f("crown_fracture", "fracture coronaire", "crown fracture");
        f("root_fracture", "fracture radiculaire", "root fracture");
        f("attrition", "attrition", "attrition");
        f("abrasion", "abrasion", "abrasion");
        f("erosion", "érosion", "erosion");
        f("non_carious_cervical_lesion", "lésion cervicale non carieuse", "non-carious cervical lesion");
        f("abfraction", "abfraction / lésion cervicale", "abfraction / cervical lesion");
        f("enamel_hypoplasia", "hypoplasie de l’émail", "enamel hypoplasia");
        f("fluorosis", "fluorose", "fluorosis");
        f("post_trauma_pulpal_discoloration", "dyschromie pulpaire après traumatisme", "pulpal discoloration after trauma");
        f("pulp_exposure", "exposition pulpaire", "pulp exposure");
        f("existing_glass_ionomer", "restauration au verre ionomère", "glass ionomer restoration");
        f("existing_temporary_filling", "obturation provisoire", "temporary filling");
        f("existing_overlay", "overlay", "overlay");
        f("existing_onlay", "onlay", "onlay");
        f("bridge_abutment", "pilier de bridge", "bridge abutment");
        f("bridge_pontic", "inter de bridge", "bridge pontic");
        f("implant_crown", "couronne sur implant", "crown on implant");
        f("temporary_crown", "couronne provisoire", "temporary crown");
        f("post_and_crown", "tenon et couronne", "post and crown");
        f("defective_restoration", "restauration défectueuse", "defective restoration");
        f("fractured_restoration", "restauration fracturée", "fractured restoration");
        f("lost_restoration", "restauration absente / perdue", "lost restoration");
        f("marginal_defect", "défaut marginal", "marginal defect");
        f("restoration_overhang", "débordement de restauration", "restoration overhang");
        f("normal_pulp", "pulpe normale", "normal pulp");
        f("reversible_pulpitis", "pulpite réversible", "reversible pulpitis");
        f("irreversible_pulpitis_symptomatic", "pulpite irréversible symptomatique", "symptomatic irreversible pulpitis");
        f("irreversible_pulpitis_asymptomatic", "pulpite irréversible asymptomatique", "asymptomatic irreversible pulpitis");
        f("endo_treatment_started", "traitement endodontique commencé mais non terminé", "root canal started, not finished");
        f("pulp_calcification", "calcification pulpaire", "pulp calcification");
        f("pulp_stone", "calcul pulpaire", "pulp stone");
        f("internal_resorption", "résorption radiculaire interne", "internal root resorption");
        f("external_resorption", "résorption radiculaire externe", "external root resorption");
        f("root_resorption", "résorption radiculaire", "root resorption");
        f("canal_obstruction", "obstruction canalaire", "canal obstruction");
        f("incomplete_root_filling", "obturation canalaire incomplète", "incomplete root filling");
        f("retreatment_required", "retraitement endodontique à envisager", "endodontic retreatment to consider");
        f("perforation", "perforation", "perforation");
        f("separated_instrument", "instrument endodontique fracturé / séparé", "separated endodontic instrument");
        f("apical_periodontitis", "parodontite apicale", "apical periodontitis");
        f("periapical_abscess", "abcès péri-apical", "periapical abscess");
        f("open_apex", "apex ouvert / dent immature", "open apex / immature tooth");
        f("hypercementosis", "hypercémentose", "hypercementosis");
        f("healthy_gingiva", "gencive saine", "healthy gingiva");
        f("gingivitis", "gingivite", "gingivitis");
        f("gingival_bleeding", "saignement gingival", "gingival bleeding");
        f("bleeding_on_probing", "saignement au sondage", "bleeding on probing");
        f("gingival_enlargement", "augmentation de volume gingival", "gingival enlargement");
        f("gingival_hyperplasia", "hyperplasie gingivale", "gingival hyperplasia");
        f("clinical_attachment_loss", "perte d’attache clinique", "clinical attachment loss");
        f("periodontitis", "parodontite", "periodontitis");
        f("gingival_abscess", "abcès gingival", "gingival abscess");
        f("periodontal_abscess", "abcès parodontal", "periodontal abscess");
        f("gingival_ulceration", "ulcération gingivale", "gingival ulceration");
        f("gingival_lesion", "lésion gingivale", "gingival lesion");
        f("gingival_pigmentation", "pigmentation gingivale", "gingival pigmentation");
        f("furcation_involvement", "atteinte de furcation", "furcation involvement");
        f("suppuration", "suppuration", "suppuration");
        f("mucogingival_anomaly", "anomalie mucogingivale", "mucogingival anomaly");
        f("peri_implant_mucositis", "mucosite péri-implantaire", "peri-implant mucositis");
        f("peri_implantitis", "péri-implantite", "peri-implantitis");
        f("calculus", "tartre", "calculus (tartar)");
        f("plaque", "accumulation de plaque dentaire", "dental plaque accumulation");
        f("crowding", "encombrement dentaire", "dental crowding");
        f("spacing", "espacement / diastème", "spacing / diastema");
        f("rotation", "rotation", "rotation");
        f("tilting", "version / inclinaison", "version / tilting");
        f("buccal_displacement", "déplacement vestibulaire", "buccal displacement");
        f("lingual_displacement", "déplacement lingual / palatin", "lingual / palatal displacement");
        f("displacement", "déplacement dentaire", "tooth displacement");
        f("intrusion", "intrusion", "intrusion");
        f("extrusion", "égression / extrusion", "egression / extrusion");
        f("mesial_migration", "migration mésiale", "mesial migration");
        f("distal_migration", "migration distale", "distal migration");
        f("crossbite", "articulé croisé", "crossbite");
        f("open_bite", "béance", "open bite");
        f("deep_bite", "supraclusion / occlusion profonde", "deep bite");
        f("traumatic_occlusion", "occlusion traumatique", "traumatic occlusion");
        f("occlusal_interference", "interférence occlusale", "occlusal interference");
        f("tooth_migration", "migration dentaire", "tooth migration");
        f("concussion", "contusion dentaire (concussion)", "concussion");
        f("subluxation", "subluxation", "subluxation");
        f("lateral_luxation", "luxation latérale", "lateral luxation");
        f("extrusive_luxation", "luxation extrusive", "extrusive luxation");
        f("intrusive_luxation", "luxation intrusive", "intrusive luxation");
        f("enamel_fracture", "fracture amélaire", "enamel fracture");
        f("soft_tissue_trauma", "traumatisme des tissus mous", "soft tissue trauma");
        f("ankylosis", "ankylose", "ankylosis");
        f("percussion_sensitivity", "sensibilité à la percussion", "percussion sensitivity");
    }

    private static final Pattern TOOTH_NUMBER =
            // Not part of a longer number, a decimal, a time or a ratio ("1.6",
            // "10:16", "16/20") — but a tooth may end a sentence or a list.
            Pattern.compile("(?<!\\d)(?<!\\d[.,:/])([1-8][1-8])(?!\\d)(?![.,:/]\\d)(?!\\s*%)");
    /** A two-digit number followed by one of these is a quantity, not a tooth. */
    private static final Pattern QUANTITY_AFTER = Pattern.compile(
            "^\\s*(?:mois|ans?|jours?|semaines?|heures?|h\\b|mm|cm|mg|ml|months?|years?|days?|weeks?|hours?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY_BEFORE = Pattern.compile(
            "(?:type|grade|stade|stage|classe|class)\\s*$", Pattern.CASE_INSENSITIVE);

    private final boolean french;
    private final List<Entry> entries = new ArrayList<>();

    private ConsultationRecords(boolean french) {
        this.french = french;
    }

    /**
     * @param language the summary language setting ("French", "English", …)
     */
    public static ConsultationRecords of(List<Map.Entry<String, String>> intentsAndEntities, String language,
                                         ObjectMapper objectMapper) {
        ConsultationRecords records = new ConsultationRecords(
                language == null || !language.trim().toLowerCase(Locale.ROOT).startsWith("en"));
        for (Map.Entry<String, String> row : intentsAndEntities) {
            JsonNode entities;
            try {
                entities = row.getValue() == null ? objectMapper.createObjectNode() : objectMapper.readTree(row.getValue());
            } catch (Exception e) {
                continue;
            }
            Entry entry = records.render(row.getKey(), entities);
            if (entry != null) {
                records.entries.add(entry);
            }
        }
        return records;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Teeth the examination recorded findings on — each must appear in a summary. */
    public Set<String> findingTeeth() {
        Set<String> teeth = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if ("findings".equals(entry.kind()) && entry.fdi() != null) teeth.add(entry.fdi());
        }
        return teeth;
    }

    /** Every tooth mentioned anywhere in the records — the only teeth a summary may name. */
    public Set<String> mentionedTeeth() {
        Set<String> teeth = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry.fdi() != null) teeth.add(entry.fdi());
        }
        return teeth;
    }

    /** Tooth numbers in {@code summary} that the records never mention. */
    public Set<String> unverifiedTeeth(String summary) {
        Set<String> unknown = new LinkedHashSet<>(toothNumbersIn(summary));
        unknown.removeAll(mentionedTeeth());
        return unknown;
    }

    /** Teeth with a recorded finding that {@code summary} leaves out. */
    public Set<String> missingTeeth(String summary) {
        Set<String> missing = new LinkedHashSet<>(findingTeeth());
        missing.removeAll(toothNumbersIn(summary));
        return missing;
    }

    static Set<String> toothNumbersIn(String text) {
        Set<String> teeth = new LinkedHashSet<>();
        if (text == null) return teeth;
        Matcher m = TOOTH_NUMBER.matcher(text);
        while (m.find()) {
            String after = text.substring(m.end());
            String before = text.substring(Math.max(0, m.start() - 12), m.start());
            if (QUANTITY_AFTER.matcher(after).find() || QUANTITY_BEFORE.matcher(before).find()) {
                continue;
            }
            teeth.add(m.group(1));
        }
        return teeth;
    }

    /** The records as the model's input: one line each, in dictation order. */
    public String asPromptLines() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append("- ").append(entry.line()).append('\n');
        }
        return out.toString();
    }

    /**
     * The summary without a model: the records grouped under headings. Used
     * when generation is off, unconfigured, unreachable, or produced text
     * that failed verification.
     */
    public String narrative() {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String teethHeading = french ? "Constatations par dent" : "Findings by tooth";
        String historyHeading = french ? "Antécédents et allergies" : "History and allergies";
        String notesHeading = french ? "Notes" : "Notes";
        String withdrawnHeading = french ? "Retraits" : "Withdrawn";
        for (Entry entry : entries) {
            String heading = switch (entry.kind()) {
                case "findings" -> teethHeading;
                case "allergy", "history" -> historyHeading;
                case "retraction" -> withdrawnHeading;
                default -> notesHeading;
            };
            sections.computeIfAbsent(heading, k -> new ArrayList<>()).add(entry.line());
        }
        StringBuilder out = new StringBuilder();
        for (String heading : List.of(teethHeading, historyHeading, notesHeading, withdrawnHeading)) {
            List<String> lines = sections.get(heading);
            if (lines == null) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(heading).append('\n');
            lines.forEach(line -> out.append("- ").append(line).append('\n'));
        }
        return out.toString().trim();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private Entry render(String intent, JsonNode e) {
        return switch (intent == null ? "" : intent) {
            case "clinical.addFindings" -> findings(e);
            case "clinical.retractFindings" -> retraction(e);
            case "clinical.addNote" -> note(e);
            case "clinical.addAllergy" -> allergy(e);
            case "clinical.addMedicalHistory" -> history(e);
            default -> null;
        };
    }

    private Entry findings(JsonNode e) {
        String fdi = text(e, "fdi");
        List<String> parts = new ArrayList<>();
        for (JsonNode finding : e.path("findings")) {
            String code = text(finding, "code");
            if (code == null) continue;
            StringBuilder part = new StringBuilder(label(code));
            List<String> qualifiers = new ArrayList<>();
            String severity = text(finding, "severity");
            if (severity != null) qualifiers.add(pick(SEVERITIES.get(severity.toUpperCase(Locale.ROOT)), severity));
            String surface = text(finding, "surface");
            if (surface != null) qualifiers.add(surfaceText(surface));
            String note = text(finding, "note");
            if (note != null) qualifiers.add(french ? "« " + note + " »" : "\"" + note + "\"");
            if (!qualifiers.isEmpty()) part.append(" (").append(String.join(", ", qualifiers)).append(')');
            // Said as a suspicion, it stays one in the summary: "suspicion de carie", never "carie".
            if ("SUSPECTED".equalsIgnoreCase(text(finding, "certainty"))) {
                part.insert(0, french ? "suspicion de " : "suspected ");
            }
            parts.add(part.toString());
        }
        if (parts.isEmpty()) return null;
        String note = text(e, "note");
        String line = (french ? "Dent " : "Tooth ") + fdi + colon() + String.join(french ? " ; " : "; ", parts)
                + (note != null ? (french ? " — « " + note + " »" : " — \"" + note + "\"") : "");
        return new Entry("findings", fdi, line);
    }

    private Entry retraction(JsonNode e) {
        String fdi = text(e, "fdi");
        List<String> labels = new ArrayList<>();
        for (JsonNode code : e.path("codes")) labels.add(label(code.asText()));
        if (labels.isEmpty()) return null;
        String line = (french ? "Retiré de la dent " : "Withdrawn from tooth ") + fdi + colon() + String.join(", ", labels);
        return new Entry("retraction", fdi, line);
    }

    private Entry note(JsonNode e) {
        String content = text(e, "content");
        if (content == null) return null;
        String category = text(e, "category");
        String fdi = text(e, "fdi");
        String prefix;
        if ("FOLLOW_UP".equals(category)) {
            prefix = french ? "Contrôle" : "Follow-up";
        } else {
            prefix = french ? "Note" : "Note";
            if (fdi != null) prefix += french ? " (dent " + fdi + ")" : " (tooth " + fdi + ")";
        }
        return new Entry("note", fdi, prefix + colon() + content);
    }

    private Entry allergy(JsonNode e) {
        String substance = text(e, "substance");
        if (substance == null) return null;
        StringBuilder line = new StringBuilder(french ? "Allergie : " : "Allergy: ").append(substance);
        String reaction = text(e, "reaction");
        if (reaction != null) line.append(french ? " (réaction : " : " (reaction: ").append(reaction).append(')');
        String severity = text(e, "severity");
        if (severity != null) line.append(", ").append(pick(SEVERITIES.get(severity.toUpperCase(Locale.ROOT)), severity));
        return new Entry("allergy", null, line.toString());
    }

    private Entry history(JsonNode e) {
        String label = text(e, "label");
        if (label == null) return null;
        boolean dental = "DENTAL_HISTORY".equals(text(e, "category"));
        String prefix = french
                ? (dental ? "Antécédent dentaire : " : "Antécédent médical : ")
                : (dental ? "Dental history: " : "Medical history: ");
        String detail = text(e, "detail");
        return new Entry("history", null, prefix + label + (detail != null ? " (" + detail + ")" : ""));
    }

    /** French puts a space before the colon; English does not. */
    private String colon() {
        return french ? " : " : ": ";
    }

    /** "mesial-occlusal" is two surfaces; each is said in full, joined, never dropped. */
    private String surfaceText(String surface) {
        List<String> parts = new ArrayList<>();
        for (String part : surface.toLowerCase(Locale.ROOT).split("-")) {
            if (!part.isBlank()) parts.add(pick(SURFACES.get(part), part));
        }
        return String.join(french ? " + " : " + ", parts);
    }

    private String label(String code) {
        return pick(FINDINGS.get(code), code.replace('_', ' '));
    }

    private String pick(String[] frEn, String fallback) {
        return frEn == null ? fallback : frEn[french ? 0 : 1];
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }
}
