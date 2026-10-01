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
