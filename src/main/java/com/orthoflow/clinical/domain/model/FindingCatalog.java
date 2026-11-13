package com.orthoflow.clinical.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The canonical vocabulary of tooth findings, and the authority on which
 * codes exist at all.
 *
 * <p>A voice command's finding code arrives from the browser, so it is client
 * input and cannot be trusted: an unrecognised code is rejected at the
 * application boundary rather than written to a clinical record. The frontend
 * lexicon (frontend/src/app/core/voice/clinical-lexicon.ts) maps spoken
 * language onto these same codes and must stay in step with this file;
 * {@code GET /api/v1/voice/lexicon} publishes the list so the frontend can
 * assert the two agree at startup instead of discovering a drift when a
 * doctor dictates a finding that silently 400s.
 *
 * <p>Each code also declares the legacy single-status value it implies, which
 * is how the 2D odontogram and 3D viewer keep working unchanged: they still
 * read one {@code ToothState.status} per tooth, now derived from the findings
 * by {@link #primaryStatus}.
 */
public final class FindingCatalog {

    /** Definition of one finding code. */
    public record Definition(String code, FindingKind kind, String impliedStatus, int statusPriority) {}

    /**
     * Priority resolves the case where several findings coexist on one tooth
     * and the chart can only paint one colour. Ordered by what a clinician
     * needs to see first when scanning a chart: absence of the tooth beats a
     * restoration, a restoration beats active pathology on it, pathology beats
     * a plain filling. Findings with a null implied status (sensitivity,
     * monitor, a note) never drive the colour but are still recorded.
     */
    private static final Map<String, Definition> CATALOG = new LinkedHashMap<>();

    private static void def(String code, FindingKind kind, String impliedStatus, int priority) {
        CATALOG.put(code, new Definition(code, kind, impliedStatus, priority));
    }

    static {
        // ── Existing restorations and structures ────────────────────────
        def("existing_crown",       FindingKind.EXISTING, "crown",      70);
        def("existing_bridge",      FindingKind.EXISTING, "bridge",     80);
        def("existing_implant",     FindingKind.EXISTING, "implant",    90);
        def("existing_veneer",      FindingKind.EXISTING, "veneer",     65);
        def("existing_root_canal",  FindingKind.EXISTING, "root_canal", 60);
        def("existing_filling",     FindingKind.EXISTING, "composite",  40);
        def("existing_composite",   FindingKind.EXISTING, "composite",  40);
        def("existing_amalgam",     FindingKind.EXISTING, "amalgam",    40);
        def("existing_post",        FindingKind.EXISTING, "post",       35);
        def("existing_sealant",     FindingKind.EXISTING, null,          0);
        def("existing_deciduous",   FindingKind.EXISTING, "deciduous",  30);
        def("existing_inlay",       FindingKind.EXISTING, "composite",  40);

        // ── Conditions / pathology ──────────────────────────────────────
        def("extracted",            FindingKind.CONDITION, "extracted", 100);
        def("missing",              FindingKind.CONDITION, "missing",    95);
        def("impacted",             FindingKind.CONDITION, "impacted",   85);
        def("fracture",             FindingKind.CONDITION, "fracture",   55);
        def("caries",               FindingKind.CONDITION, "caries",     50);
        def("recurrent_caries",     FindingKind.CONDITION, "caries",     52);
        def("deep_caries",          FindingKind.CONDITION, "caries",     53);
        def("cavity",               FindingKind.CONDITION, "caries",     50);
        def("retained_root",        FindingKind.CONDITION, "fracture",   54);
        def("infection",            FindingKind.CONDITION, null,          0);
        def("abscess",              FindingKind.CONDITION, null,          0);
        def("mobility",             FindingKind.CONDITION, null,          0);
        def("sensitivity",          FindingKind.CONDITION, null,          0);
        def("pain",                 FindingKind.CONDITION, null,          0);
        def("tooth_wear",           FindingKind.CONDITION, null,          0);
        def("discoloration",        FindingKind.CONDITION, null,          0);
        def("gingival_inflammation",FindingKind.CONDITION, null,          0);
        def("periodontal_pocket",   FindingKind.CONDITION, null,          0);
        def("gingival_recession",   FindingKind.CONDITION, null,          0);
        def("plaque_calculus",      FindingKind.CONDITION, null,          0);
        def("malposition",          FindingKind.CONDITION, null,          0);
        def("crown_defective",      FindingKind.CONDITION, "crown",      71);
        def("avulsion",             FindingKind.CONDITION, "missing",    95);
        def("extensive_destruction",FindingKind.CONDITION, null,          0);
        def("pulpitis",             FindingKind.CONDITION, null,          0);
        def("necrosis",             FindingKind.CONDITION, null,          0);
        def("periapical_lesion",    FindingKind.CONDITION, null,          0);
        def("bleeding",             FindingKind.CONDITION, null,          0);
        def("swelling",             FindingKind.CONDITION, null,          0);

        // ── Treatment required ──────────────────────────────────────────
        def("crown_replacement_required", FindingKind.TREATMENT_REQUIRED, null, 0);
        def("crown_required",             FindingKind.TREATMENT_REQUIRED, null, 0);
        def("filling_required",           FindingKind.TREATMENT_REQUIRED, null, 0);
        def("extraction_required",        FindingKind.TREATMENT_REQUIRED, null, 0);
        def("root_canal_required",        FindingKind.TREATMENT_REQUIRED, null, 0);
        def("implant_required",           FindingKind.TREATMENT_REQUIRED, null, 0);
        def("bridge_required",            FindingKind.TREATMENT_REQUIRED, null, 0);
        def("veneer_required",            FindingKind.TREATMENT_REQUIRED, null, 0);
        def("scaling_required",           FindingKind.TREATMENT_REQUIRED, null, 0);
        def("sealant_required",           FindingKind.TREATMENT_REQUIRED, null, 0);
        def("restoration_required",       FindingKind.TREATMENT_REQUIRED, null, 0);
        def("periodontal_treatment_required", FindingKind.TREATMENT_REQUIRED, null, 0);

        // ── Observations ────────────────────────────────────────────────
        def("normal",     FindingKind.OBSERVATION, null, 0);
        def("monitor",    FindingKind.OBSERVATION, null, 0);
        def("follow_up",  FindingKind.OBSERVATION, null, 0);
        def("note",       FindingKind.OBSERVATION, null, 0);

        // ── The clinical referential ────────────────────────────────────
        // "Référentiel des états cliniques dentaires": every state of its catalogue that the
        // codes above did not already name, from the development of the tooth to trauma. A state
        // that implies a legacy status (a root fracture is a "fracture") keeps the chart's one
        // painted status in step; the others are recorded and shown without driving it. The
        // frontend's voice lexicon (clinical-lexicon-referential.ts) says each one in French,
        // English and Arabic, and its finding-referential.ts draws them: the three move together.
        def("agenesis", FindingKind.CONDITION, "missing", 96);
        def("partially_erupted", FindingKind.CONDITION, null, 0);
        def("unerupted", FindingKind.CONDITION, "impacted", 84);
        def("erupting", FindingKind.CONDITION, null, 0);
        def("persistent_deciduous", FindingKind.CONDITION, null, 0);
        def("supernumerary", FindingKind.CONDITION, null, 0);
        def("permanent_tooth", FindingKind.OBSERVATION, null, 0);
        def("ectopic_eruption", FindingKind.CONDITION, null, 0);
        def("white_spot_lesion", FindingKind.CONDITION, null, 0);
        def("demineralisation", FindingKind.CONDITION, null, 0);
        def("cracked_tooth", FindingKind.CONDITION, "fracture", 54);
        def("craze_lines", FindingKind.CONDITION, null, 0);
        def("cusp_fracture", FindingKind.CONDITION, "fracture", 55);
        def("enamel_dentin_fracture", FindingKind.CONDITION, "fracture", 55);
        def("complicated_crown_fracture", FindingKind.CONDITION, "fracture", 56);
        def("crown_root_fracture", FindingKind.CONDITION, "fracture", 56);
        def("crown_fracture", FindingKind.CONDITION, "fracture", 55);
        def("root_fracture", FindingKind.CONDITION, "fracture", 56);
        def("attrition", FindingKind.CONDITION, null, 0);
        def("abrasion", FindingKind.CONDITION, null, 0);
        def("erosion", FindingKind.CONDITION, null, 0);
        def("non_carious_cervical_lesion", FindingKind.CONDITION, null, 0);
        def("abfraction", FindingKind.CONDITION, null, 0);
        def("enamel_hypoplasia", FindingKind.CONDITION, null, 0);
        def("fluorosis", FindingKind.CONDITION, null, 0);
        def("post_trauma_pulpal_discoloration", FindingKind.CONDITION, null, 0);
        def("pulp_exposure", FindingKind.CONDITION, null, 0);
        def("existing_glass_ionomer", FindingKind.EXISTING, "composite", 40);
        def("existing_temporary_filling", FindingKind.EXISTING, "composite", 38);
        def("existing_overlay", FindingKind.EXISTING, "composite", 42);
        def("existing_onlay", FindingKind.EXISTING, "composite", 41);
        def("bridge_abutment", FindingKind.EXISTING, "bridge", 80);
        def("bridge_pontic", FindingKind.EXISTING, "bridge", 80);
        def("implant_crown", FindingKind.EXISTING, "implant", 91);
        def("temporary_crown", FindingKind.EXISTING, "crown", 69);
        def("post_and_crown", FindingKind.EXISTING, "crown", 72);
        def("defective_restoration", FindingKind.CONDITION, null, 0);
        def("fractured_restoration", FindingKind.CONDITION, null, 0);
        def("lost_restoration", FindingKind.CONDITION, null, 0);
        def("marginal_defect", FindingKind.CONDITION, null, 0);
        def("restoration_overhang", FindingKind.CONDITION, null, 0);
        def("normal_pulp", FindingKind.OBSERVATION, null, 0);
        def("reversible_pulpitis", FindingKind.CONDITION, null, 0);
        def("irreversible_pulpitis_symptomatic", FindingKind.CONDITION, null, 0);
        def("irreversible_pulpitis_asymptomatic", FindingKind.CONDITION, null, 0);
        def("endo_treatment_started", FindingKind.CONDITION, null, 0);
        def("pulp_calcification", FindingKind.CONDITION, null, 0);
        def("pulp_stone", FindingKind.CONDITION, null, 0);
        def("internal_resorption", FindingKind.CONDITION, null, 0);
        def("external_resorption", FindingKind.CONDITION, null, 0);
        def("root_resorption", FindingKind.CONDITION, null, 0);
        def("canal_obstruction", FindingKind.CONDITION, null, 0);
        def("incomplete_root_filling", FindingKind.CONDITION, null, 0);
        def("retreatment_required", FindingKind.TREATMENT_REQUIRED, null, 0);
        def("perforation", FindingKind.CONDITION, null, 0);
        def("separated_instrument", FindingKind.CONDITION, null, 0);
        def("apical_periodontitis", FindingKind.CONDITION, null, 0);
        def("periapical_abscess", FindingKind.CONDITION, null, 0);
        def("open_apex", FindingKind.CONDITION, null, 0);
        def("hypercementosis", FindingKind.CONDITION, null, 0);
        def("healthy_gingiva", FindingKind.OBSERVATION, null, 0);
        def("gingivitis", FindingKind.CONDITION, null, 0);
        def("gingival_bleeding", FindingKind.CONDITION, null, 0);
        def("bleeding_on_probing", FindingKind.CONDITION, null, 0);
        def("gingival_enlargement", FindingKind.CONDITION, null, 0);
        def("gingival_hyperplasia", FindingKind.CONDITION, null, 0);
        def("clinical_attachment_loss", FindingKind.CONDITION, null, 0);
        def("periodontitis", FindingKind.CONDITION, null, 0);
        def("gingival_abscess", FindingKind.CONDITION, null, 0);
        def("periodontal_abscess", FindingKind.CONDITION, null, 0);
        def("gingival_ulceration", FindingKind.CONDITION, null, 0);
        def("gingival_lesion", FindingKind.CONDITION, null, 0);
        def("gingival_pigmentation", FindingKind.CONDITION, null, 0);
        def("furcation_involvement", FindingKind.CONDITION, null, 0);
        def("suppuration", FindingKind.CONDITION, null, 0);
        def("mucogingival_anomaly", FindingKind.CONDITION, null, 0);
        def("peri_implant_mucositis", FindingKind.CONDITION, null, 0);
        def("peri_implantitis", FindingKind.CONDITION, null, 0);
        def("calculus", FindingKind.CONDITION, null, 0);
        def("plaque", FindingKind.CONDITION, null, 0);
        def("crowding", FindingKind.CONDITION, null, 0);
        def("spacing", FindingKind.CONDITION, null, 0);
        def("rotation", FindingKind.CONDITION, null, 0);
        def("tilting", FindingKind.CONDITION, null, 0);
        def("buccal_displacement", FindingKind.CONDITION, null, 0);
        def("lingual_displacement", FindingKind.CONDITION, null, 0);
        def("displacement", FindingKind.CONDITION, null, 0);
        def("intrusion", FindingKind.CONDITION, null, 0);
        def("extrusion", FindingKind.CONDITION, null, 0);
        def("mesial_migration", FindingKind.CONDITION, null, 0);
        def("distal_migration", FindingKind.CONDITION, null, 0);
        def("crossbite", FindingKind.CONDITION, null, 0);
        def("open_bite", FindingKind.CONDITION, null, 0);
        def("deep_bite", FindingKind.CONDITION, null, 0);
        def("traumatic_occlusion", FindingKind.CONDITION, null, 0);
        def("occlusal_interference", FindingKind.CONDITION, null, 0);
        def("tooth_migration", FindingKind.CONDITION, null, 0);
        def("concussion", FindingKind.CONDITION, null, 0);
        def("subluxation", FindingKind.CONDITION, null, 0);
        def("lateral_luxation", FindingKind.CONDITION, null, 0);
        def("extrusive_luxation", FindingKind.CONDITION, null, 0);
        def("intrusive_luxation", FindingKind.CONDITION, null, 0);
        def("enamel_fracture", FindingKind.CONDITION, "fracture", 52);
        def("soft_tissue_trauma", FindingKind.CONDITION, null, 0);
        def("ankylosis", FindingKind.CONDITION, null, 0);
        def("percussion_sensitivity", FindingKind.CONDITION, null, 0);
    }

    private FindingCatalog() {}

    public static boolean isKnown(String code) {
        return code != null && CATALOG.containsKey(code);
    }

    public static Optional<Definition> get(String code) {
        return Optional.ofNullable(code == null ? null : CATALOG.get(code));
    }

    public static Set<String> codes() {
        return CATALOG.keySet();
    }

    public static Map<String, Definition> all() {
        return Map.copyOf(CATALOG);
    }

    /**
     * The single status the odontogram should paint for a tooth carrying these
     * findings. Returns {@code "present"} when nothing among them implies a
     * status — a tooth whose only finding is "sensitivity" is still, as far as
     * the chart is concerned, a normal-looking tooth.
     */
    public static String primaryStatus(Iterable<String> activeFindingCodes) {
        String best = "present";
        int bestPriority = -1;
        for (String code : activeFindingCodes) {
            Definition def = CATALOG.get(code);
            if (def == null || def.impliedStatus() == null) continue;
            if (def.statusPriority() > bestPriority) {
                bestPriority = def.statusPriority();
                best = def.impliedStatus();
            }
        }
        return best;
    }
}
