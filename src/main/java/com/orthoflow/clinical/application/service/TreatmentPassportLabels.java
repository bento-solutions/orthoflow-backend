package com.orthoflow.clinical.application.service;

import java.util.Map;

/**
 * The words of the treatment passport in French, English and Arabic: the
 * document is handed to a patient, who gives it to a practitioner who may read
 * a different language than the clinic's, so it is printed in the language the
 * doctor picks and the machine-readable copy carries codes, never words.
 *
 * <p>Finding, surface and gum wording is the same as the application's own
 * (frontend/public/i18n), so what the doctor saw on the chart is what is
 * printed. A code with no entry prints as itself in readable form.
 */
final class TreatmentPassportLabels {

    /** Column order of every table below. */
    private static final int EN = 0;
    private static final int FR = 1;
    private static final int AR = 2;

    private static final Map<String, String[]> FINDINGS = Map.ofEntries(
        Map.entry("caries", new String[] {"Caries", "Carie", "تسوس"}),
        Map.entry("recurrent_caries", new String[] {"Recurrent caries", "Carie récidivante", "تسوس متكرر"}),
        Map.entry("deep_caries", new String[] {"Deep caries", "Carie profonde", "تسوس عميق"}),
        Map.entry("fracture", new String[] {"Fracture", "Fracture", "كسر"}),
        Map.entry("tooth_wear", new String[] {"Tooth wear", "Usure dentaire", "تآكل الأسنان"}),
        Map.entry("discoloration", new String[] {"Discoloration", "Dyschromie", "تغيّر اللون"}),
        Map.entry("crown_defective", new String[] {"Defective crown", "Couronne défectueuse", "تاج معيب"}),
        Map.entry("mobility", new String[] {"Mobility", "Mobilité", "حركة السن"}),
        Map.entry("sensitivity", new String[] {"Sensitivity", "Sensibilité", "حساسية"}),
        Map.entry("pulpitis", new String[] {"Pulpitis", "Pulpite", "التهاب اللب"}),
        Map.entry("abscess", new String[] {"Abscess", "Abcès", "خراج"}),
        Map.entry("periapical_lesion", new String[] {"Periapical lesion", "Lésion périapicale", "آفة حول الذروة"}),
        Map.entry("retained_root", new String[] {"Retained root", "Racine résiduelle", "جذر متبقٍ"}),
        Map.entry("impacted", new String[] {"Impacted", "Incluse", "منطمر"}),
        Map.entry("missing", new String[] {"Missing", "Absente", "مفقود"}),
        Map.entry("extracted", new String[] {"Extracted", "Extraite", "مخلوع"}),
        Map.entry("existing_composite", new String[] {"Composite filling", "Obturation composite", "حشوة كمبوزيت"}),
        Map.entry("existing_amalgam", new String[] {"Amalgam filling", "Obturation amalgame", "حشوة أملغم"}),
        Map.entry("existing_inlay", new String[] {"Inlay / onlay", "Inlay / onlay", "إنلاي / أونلاي"}),
        Map.entry("existing_sealant", new String[] {"Sealant", "Scellement de sillons", "سدّ الشقوق"}),
        Map.entry("existing_crown", new String[] {"Crown", "Couronne", "تاج"}),
        Map.entry("existing_bridge", new String[] {"Bridge", "Bridge", "جسر"}),
        Map.entry("existing_veneer", new String[] {"Veneer", "Facette", "قشرة (فينير)"}),
        Map.entry("existing_root_canal", new String[] {"Root canal treatment", "Traitement endodontique", "علاج العصب"}),
        Map.entry("existing_post", new String[] {"Post", "Pivot (tenon)", "وتد"}),
        Map.entry("existing_implant", new String[] {"Implant", "Implant", "زرعة"}),
        Map.entry("existing_deciduous", new String[] {"Primary tooth", "Dent temporaire", "سن لبني"}),
        Map.entry("filling_required", new String[] {"Filling needed", "Obturation à faire", "حشوة مطلوبة"}),
        Map.entry("sealant_required", new String[] {"Sealant needed", "Scellement à faire", "سدّ شقوق مطلوب"}),
        Map.entry("restoration_required", new String[] {"Restoration needed", "Restauration à faire", "ترميم مطلوب"}),
        Map.entry("crown_required", new String[] {"Crown needed", "Couronne à faire", "تاج مطلوب"}),
        Map.entry("crown_replacement_required", new String[] {"Crown replacement needed", "Remplacement de couronne à faire", "استبدال التاج مطلوب"}),
        Map.entry("root_canal_required", new String[] {"Root canal needed", "Dévitalisation à faire", "علاج عصب مطلوب"}),
        Map.entry("extraction_required", new String[] {"Extraction needed", "Extraction à faire", "خلع مطلوب"}),
        Map.entry("implant_required", new String[] {"Implant needed", "Implant à faire", "زرعة مطلوبة"}),
        Map.entry("bridge_required", new String[] {"Bridge needed", "Bridge à faire", "جسر مطلوب"}),
        Map.entry("veneer_required", new String[] {"Veneer needed", "Facette à faire", "قشرة مطلوبة"}),
        Map.entry("monitor", new String[] {"Monitor", "À surveiller", "مراقبة"}),
        Map.entry("follow_up", new String[] {"Follow-up", "Suivi", "متابعة"})
    );

    private static final Map<String, String[]> SURFACES = Map.ofEntries(
        Map.entry("mesial", new String[] {"Mesial", "Mésiale", "إنسية"}),
        Map.entry("occlusal", new String[] {"Occlusal", "Occlusale", "إطباقية"}),
        Map.entry("distal", new String[] {"Distal", "Distale", "وحشية"}),
        Map.entry("incisal", new String[] {"Incisal", "Incisive", "قاطعة"}),
        Map.entry("buccal", new String[] {"Buccal", "Vestibulaire", "دهليزية"}),
        Map.entry("lingual", new String[] {"Lingual / palatal", "Linguale / palatine", "لسانية / حنكية"}),
        Map.entry("cervical", new String[] {"Cervical", "Cervicale", "عنقية"})
    );

    private static final Map<String, String[]> REGIONS = Map.ofEntries(
        Map.entry("WHOLE_MOUTH", new String[] {"Whole mouth", "Bouche entière", "الفم كاملًا"}),
        Map.entry("UPPER_RIGHT", new String[] {"Upper right (18–14)", "Haut droite (18–14)", "العلوي الأيمن (18–14)"}),
        Map.entry("UPPER_FRONT", new String[] {"Upper front (13–23)", "Haut antérieur (13–23)", "العلوي الأمامي (13–23)"}),
        Map.entry("UPPER_LEFT", new String[] {"Upper left (24–28)", "Haut gauche (24–28)", "العلوي الأيسر (24–28)"}),
        Map.entry("LOWER_RIGHT", new String[] {"Lower right (48–44)", "Bas droite (48–44)", "السفلي الأيمن (48–44)"}),
        Map.entry("LOWER_FRONT", new String[] {"Lower front (43–33)", "Bas antérieur (43–33)", "السفلي الأمامي (43–33)"}),
        Map.entry("LOWER_LEFT", new String[] {"Lower left (34–38)", "Bas gauche (34–38)", "السفلي الأيسر (34–38)"})
    );

    private static final Map<String, String[]> CONDITIONS = Map.ofEntries(
        Map.entry("HEALTHY", new String[] {"Healthy gums", "Gencives saines", "لثة سليمة"}),
        Map.entry("GINGIVITIS", new String[] {"Gingivitis", "Gingivite", "التهاب اللثة"}),
        Map.entry("PERIODONTITIS", new String[] {"Periodontitis", "Parodontite", "التهاب دواعم السن"})
    );

    /** The wording of the document itself: key → {en, fr, ar}. */
    private static final Map<String, String[]> TEXT = Map.ofEntries(
        Map.entry("title", new String[] {"Treatment passport", "Passeport de soins dentaires", "جواز العلاجات السنية"}),
        Map.entry("intro", new String[] {
            "A summary of the dental care recorded for this patient, given to the patient to hand to another practitioner.",
            "Résumé des soins dentaires enregistrés pour ce patient, remis au patient pour un autre praticien.",
            "ملخص للعلاجات السنية المسجلة لهذا المريض، يُسلَّم للمريض ليقدمه إلى طبيب آخر."}),
        Map.entry("patient", new String[] {"Patient", "Patient", "المريض"}),
        Map.entry("birthDate", new String[] {"Date of birth", "Date de naissance", "تاريخ الميلاد"}),
        Map.entry("gender", new String[] {"Sex", "Sexe", "الجنس"}),
        Map.entry("male", new String[] {"Male", "Homme", "ذكر"}),
        Map.entry("female", new String[] {"Female", "Femme", "أنثى"}),
        Map.entry("issuedOn", new String[] {"Issued on", "Établi le", "صدر في"}),
        Map.entry("alerts", new String[] {"Allergies and medical alerts", "Allergies et alertes médicales", "الحساسية والتنبيهات الطبية"}),
        Map.entry("noAlerts", new String[] {"None recorded.", "Aucune enregistrée.", "لا شيء مسجل."}),
        Map.entry("teeth", new String[] {"Present state of the teeth", "État actuel des dents", "الحالة الحالية للأسنان"}),
        Map.entry("noTeeth", new String[] {"Nothing recorded on any tooth.", "Rien d’enregistré sur les dents.", "لا شيء مسجل على الأسنان."}),
        Map.entry("tooth", new String[] {"Tooth", "Dent", "السن"}),
        Map.entry("state", new String[] {"State", "État", "الحالة"}),
        Map.entry("log", new String[] {"Treatment log", "Journal des soins", "سجل العلاجات"}),
        Map.entry("noLog", new String[] {"No treatment recorded yet.", "Aucun soin enregistré.", "لا علاج مسجل بعد."}),
        Map.entry("date", new String[] {"Date", "Date", "التاريخ"}),
        Map.entry("work", new String[] {"Work", "Soin", "العلاج"}),
        Map.entry("doneBy", new String[] {"Done by", "Réalisé par", "أُنجز عند"}),
        Map.entry("dateUnknown", new String[] {"Date unknown", "Date inconnue", "التاريخ مجهول"}),
        Map.entry("elsewhere", new String[] {"Elsewhere", "Ailleurs", "في مكان آخر"}),
        Map.entry("thisClinic", new String[] {"This clinic", "Ce cabinet", "هذه العيادة"}),
        Map.entry("treated", new String[] {"treated", "traité", "تمت المعالجة"}),
        Map.entry("planned", new String[] {"Work still to do", "Soins restant à réaliser", "علاجات متبقية"}),
        Map.entry("noPlanned", new String[] {"None.", "Aucun.", "لا شيء."}),
        Map.entry("gums", new String[] {"Gum health", "Santé gingivale", "صحة اللثة"}),
        Map.entry("noGums", new String[] {"Not assessed.", "Non évaluée.", "لم تُقيَّم."}),
        Map.entry("area", new String[] {"Area", "Zone", "المنطقة"}),
        Map.entry("stage", new String[] {"stage", "stade", "المرحلة"}),
        Map.entry("legend", new String[] {
            "Surfaces: M mesial, D distal, O occlusal, I incisal, B buccal, L lingual or palatal, C cervical. Tooth numbers follow the FDI system.",
            "Faces : M mésiale, D distale, O occlusale, I incisive, B vestibulaire, L linguale ou palatine, C cervicale. Numérotation FDI.",
            "الأسطح: M إنسي، D وحشي، O إطباقي، I قاطع، B دهليزي، L لساني أو حنكي، C عنقي. ترقيم الأسنان حسب نظام FDI."}),
        Map.entry("consent", new String[] {
            "Issued at the patient’s request. The information belongs to the patient.",
            "Établi à la demande du patient. Ces informations appartiennent au patient.",
            "صدر بطلب من المريض. هذه المعلومات ملك للمريض."}),
        Map.entry("machineCopy", new String[] {
            "A structured copy of this passport, readable by software, is available from the clinic.",
            "Une copie structurée de ce passeport, lisible par un logiciel, est disponible auprès du cabinet.",
            "تتوفر لدى العيادة نسخة منظمة من هذا الجواز يمكن للبرمجيات قراءتها."}),
        Map.entry("signature", new String[] {"Practitioner’s stamp and signature", "Cachet et signature du praticien", "ختم وتوقيع الطبيب"})
    );

    private final int index;
    private final String language;

    private TreatmentPassportLabels(String language) {
        this.language = language;
        this.index = switch (language) {
            case "en" -> EN;
            case "ar" -> AR;
            default -> FR;
        };
    }

    static TreatmentPassportLabels of(String lang) {
        return new TreatmentPassportLabels("en".equals(lang) || "ar".equals(lang) ? lang : "fr");
    }

    String language() {
        return language;
    }

    String text(String key) {
        String[] row = TEXT.get(key);
        return row == null ? key : row[index];
    }

    String finding(String code) {
        String[] row = FINDINGS.get(code);
        if (row != null) return row[index];
        String words = code.replace('_', ' ');
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    String surface(String surface) {
        if (surface == null || surface.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String part : surface.split("-")) {
            if (!out.isEmpty()) out.append(' ').append('+').append(' ');
            String[] row = SURFACES.get(part);
            out.append(row == null ? part : row[index]);
        }
        return out.toString();
    }

    /** Dentists' shorthand: "mesial-occlusal-distal" → "MOD". */
    static String shorthand(String surface) {
        if (surface == null || surface.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String part : surface.split("-")) {
            out.append(switch (part) {
                case "mesial" -> "M";
                case "distal" -> "D";
                case "occlusal" -> "O";
                case "incisal" -> "I";
                case "buccal" -> "B";
                case "lingual" -> "L";
                case "cervical" -> "C";
                default -> "";
            });
        }
        return out.toString();
    }

    String region(String region) {
        String[] row = REGIONS.get(region);
        return row == null ? region : row[index];
    }

    String condition(String condition) {
        String[] row = CONDITIONS.get(condition);
        return row == null ? condition : row[index];
    }
}
