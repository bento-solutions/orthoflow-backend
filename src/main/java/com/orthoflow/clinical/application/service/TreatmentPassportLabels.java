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
        Map.entry("existing_inlay", new String[] {"Inlay", "Inlay", "إنلاي"}),
        Map.entry("existing_sealant", new String[] {"Sealant", "Scellement de sillons", "سدّ الشقوق"}),
        Map.entry("existing_crown", new String[] {"Crown", "Couronne", "تاج"}),
        Map.entry("existing_bridge", new String[] {"Bridge", "Bridge", "جسر"}),
        Map.entry("existing_veneer", new String[] {"Veneer", "Facette", "قشرة (فينير)"}),
        Map.entry("existing_root_canal", new String[] {"Root canal treatment", "Traitement endodontique", "علاج العصب"}),
        Map.entry("existing_post", new String[] {"Post", "Pivot (tenon)", "وتد"}),
        Map.entry("existing_implant", new String[] {"Implant", "Implant", "زرعة"}),
        Map.entry("existing_deciduous", new String[] {"Primary tooth", "Dent temporaire (déciduale)", "سن لبني"}),
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
        Map.entry("cavity", new String[] {"Cavity", "Cavité", "تجويف"}),
        Map.entry("existing_filling", new String[] {"Filling", "Obturation", "حشوة"}),
        Map.entry("avulsion", new String[] {"Avulsion", "Avulsion", "انخلاع"}),
        Map.entry("extensive_destruction", new String[] {"Extensive destruction", "Destruction étendue", "تلف واسع"}),
        Map.entry("necrosis", new String[] {"Necrosis", "Nécrose", "نخر"}),
        Map.entry("infection", new String[] {"Infection", "Infection", "عدوى"}),
        Map.entry("pain", new String[] {"Pain", "Douleur", "ألم"}),
        Map.entry("swelling", new String[] {"Swelling", "Tuméfaction", "تورّم"}),
        Map.entry("bleeding", new String[] {"Bleeding", "Saignement", "نزيف"}),
        Map.entry("malposition", new String[] {"Malposition", "Malposition", "سوء الوضع"}),
        Map.entry("gingival_inflammation", new String[] {"Gingival inflammation", "Inflammation gingivale", "التهاب اللثة"}),
        Map.entry("gingival_recession", new String[] {"Gingival recession", "Récession gingivale", "انحسار اللثة"}),
        Map.entry("periodontal_pocket", new String[] {"Periodontal pocket", "Poche parodontale", "جيب لثوي"}),
        Map.entry("plaque_calculus", new String[] {"Plaque and calculus", "Plaque et tartre", "لويحة وجير"}),
        Map.entry("monitor", new String[] {"Monitor", "À surveiller", "مراقبة"}),
        Map.entry("follow_up", new String[] {"Follow-up", "Suivi", "متابعة"}),
        Map.entry("agenesis", new String[] {"Agenesis", "Agénésie dentaire", "غياب خلقي للسن"}),
        Map.entry("partially_erupted", new String[] {"Partially erupted", "Partiellement éruptée", "بزوغ جزئي"}),
        Map.entry("unerupted", new String[] {"Unerupted", "Non éruptée", "غير بازغ"}),
        Map.entry("erupting", new String[] {"Erupting", "En cours d’éruption", "في طور البزوغ"}),
        Map.entry("persistent_deciduous", new String[] {"Persistent deciduous tooth", "Dent temporaire persistante", "سن لبني باقٍ"}),
        Map.entry("supernumerary", new String[] {"Supernumerary tooth", "Dent surnuméraire", "سن زائد"}),
        Map.entry("permanent_tooth", new String[] {"Permanent tooth", "Dent permanente", "سن دائم"}),
        Map.entry("ectopic_eruption", new String[] {"Ectopic eruption", "Éruption ectopique", "بزوغ منتبذ"}),
        Map.entry("white_spot_lesion", new String[] {"White spot lesion", "Lésion amélaire initiale / tache blanche", "بقعة بيضاء (آفة مينائية أولية)"}),
        Map.entry("demineralisation", new String[] {"Demineralisation", "Déminéralisation", "نقص التمعدن"}),
        Map.entry("cracked_tooth", new String[] {"Cracked tooth", "Dent fissurée", "سن متشقق"}),
        Map.entry("craze_lines", new String[] {"Enamel craze lines", "Fêlures amélaires (craze lines)", "تشققات المينا الدقيقة"}),
        Map.entry("cusp_fracture", new String[] {"Cusp fracture", "Fracture cuspidienne", "كسر الحدبة"}),
        Map.entry("enamel_dentin_fracture", new String[] {"Enamel-dentin fracture", "Fracture amélo-dentinaire", "كسر مينائي عاجي"}),
        Map.entry("complicated_crown_fracture", new String[] {"Complicated crown fracture", "Fracture coronaire compliquée", "كسر تاجي معقد"}),
        Map.entry("crown_root_fracture", new String[] {"Crown-root fracture", "Fracture corono-radiculaire", "كسر تاجي جذري"}),
        Map.entry("crown_fracture", new String[] {"Crown fracture", "Fracture coronaire", "كسر التاج"}),
        Map.entry("root_fracture", new String[] {"Root fracture", "Fracture radiculaire", "كسر جذري"}),
        Map.entry("attrition", new String[] {"Attrition", "Attrition", "تآكل احتكاكي"}),
        Map.entry("abrasion", new String[] {"Abrasion", "Abrasion", "سحج الأسنان"}),
        Map.entry("erosion", new String[] {"Erosion", "Érosion", "حتّ الأسنان"}),
        Map.entry("non_carious_cervical_lesion", new String[] {"Non-carious cervical lesion", "Lésion cervicale non carieuse", "آفة عنقية غير تسوسية"}),
        Map.entry("abfraction", new String[] {"Abfraction / cervical lesion", "Abfraction / lésion cervicale", "آفة عنقية إجهادية"}),
        Map.entry("enamel_hypoplasia", new String[] {"Enamel hypoplasia", "Hypoplasie de l’émail", "نقص تنسج المينا"}),
        Map.entry("fluorosis", new String[] {"Fluorosis", "Fluorose", "التسمم بالفلور"}),
        Map.entry("post_trauma_pulpal_discoloration", new String[] {"Pulpal discoloration after trauma", "Dyschromie pulpaire après traumatisme", "تغيّر لون اللب بعد الرض"}),
        Map.entry("pulp_exposure", new String[] {"Pulp exposure", "Exposition pulpaire", "انكشاف اللب"}),
        Map.entry("existing_glass_ionomer", new String[] {"Glass ionomer restoration", "Restauration au verre ionomère", "حشوة زجاجية شاردة"}),
        Map.entry("existing_temporary_filling", new String[] {"Temporary filling", "Obturation provisoire", "حشوة مؤقتة"}),
        Map.entry("existing_overlay", new String[] {"Overlay", "Overlay", "أوفرلاي"}),
        Map.entry("existing_onlay", new String[] {"Onlay", "Onlay", "أونلاي"}),
        Map.entry("bridge_abutment", new String[] {"Bridge abutment", "Pilier de bridge", "دعامة جسر"}),
        Map.entry("bridge_pontic", new String[] {"Bridge pontic", "Inter de bridge", "وسيط الجسر"}),
        Map.entry("implant_crown", new String[] {"Crown on implant", "Couronne sur implant", "تاج على زرعة"}),
        Map.entry("temporary_crown", new String[] {"Temporary crown", "Couronne provisoire", "تاج مؤقت"}),
        Map.entry("post_and_crown", new String[] {"Post and crown", "Tenon et couronne", "وتد وتاج"}),
        Map.entry("defective_restoration", new String[] {"Defective restoration", "Restauration défectueuse", "ترميم معيب"}),
        Map.entry("fractured_restoration", new String[] {"Fractured restoration", "Restauration fracturée", "ترميم مكسور"}),
        Map.entry("lost_restoration", new String[] {"Lost restoration", "Restauration absente / perdue", "ترميم مفقود"}),
        Map.entry("marginal_defect", new String[] {"Marginal defect", "Défaut marginal", "عيب حافّي"}),
        Map.entry("restoration_overhang", new String[] {"Restoration overhang", "Débordement de restauration", "زيادة في حافة الترميم"}),
        Map.entry("normal_pulp", new String[] {"Normal pulp", "Pulpe normale", "لب سليم"}),
        Map.entry("reversible_pulpitis", new String[] {"Reversible pulpitis", "Pulpite réversible", "التهاب لب عكوس"}),
        Map.entry("irreversible_pulpitis_symptomatic", new String[] {"Symptomatic irreversible pulpitis", "Pulpite irréversible symptomatique", "التهاب لب لا عكوس مع أعراض"}),
        Map.entry("irreversible_pulpitis_asymptomatic", new String[] {"Asymptomatic irreversible pulpitis", "Pulpite irréversible asymptomatique", "التهاب لب لا عكوس بلا أعراض"}),
        Map.entry("endo_treatment_started", new String[] {"Root canal started, not finished", "Traitement endodontique commencé mais non terminé", "علاج لبي بدأ ولم يكتمل"}),
        Map.entry("pulp_calcification", new String[] {"Pulp calcification", "Calcification pulpaire", "تكلّس اللب"}),
        Map.entry("pulp_stone", new String[] {"Pulp stone", "Calcul pulpaire", "حصاة لبية"}),
        Map.entry("internal_resorption", new String[] {"Internal root resorption", "Résorption radiculaire interne", "امتصاص جذري داخلي"}),
        Map.entry("external_resorption", new String[] {"External root resorption", "Résorption radiculaire externe", "امتصاص جذري خارجي"}),
        Map.entry("root_resorption", new String[] {"Root resorption", "Résorption radiculaire", "امتصاص جذري"}),
        Map.entry("canal_obstruction", new String[] {"Canal obstruction", "Obstruction canalaire", "انسداد القناة"}),
        Map.entry("incomplete_root_filling", new String[] {"Incomplete root filling", "Obturation canalaire incomplète", "حشو قنوي غير كامل"}),
        Map.entry("retreatment_required", new String[] {"Endodontic retreatment to consider", "Retraitement endodontique à envisager", "إعادة العلاج اللبي مطلوبة"}),
        Map.entry("perforation", new String[] {"Perforation", "Perforation", "انثقاب"}),
        Map.entry("separated_instrument", new String[] {"Separated endodontic instrument", "Instrument endodontique fracturé / séparé", "أداة لبية مكسورة"}),
        Map.entry("apical_periodontitis", new String[] {"Apical periodontitis", "Parodontite apicale", "التهاب دواعم الذروة"}),
        Map.entry("periapical_abscess", new String[] {"Periapical abscess", "Abcès péri-apical", "خراج حول الذروة"}),
        Map.entry("open_apex", new String[] {"Open apex / immature tooth", "Apex ouvert / dent immature", "ذروة مفتوحة / سن غير ناضج"}),
        Map.entry("hypercementosis", new String[] {"Hypercementosis", "Hypercémentose", "فرط الملاط"}),
        Map.entry("healthy_gingiva", new String[] {"Healthy gingiva", "Gencive saine", "لثة سليمة"}),
        Map.entry("gingivitis", new String[] {"Gingivitis", "Gingivite", "التهاب اللثة الموضعي"}),
        Map.entry("gingival_bleeding", new String[] {"Gingival bleeding", "Saignement gingival", "نزيف لثوي"}),
        Map.entry("bleeding_on_probing", new String[] {"Bleeding on probing", "Saignement au sondage", "نزف عند السبر"}),
        Map.entry("gingival_enlargement", new String[] {"Gingival enlargement", "Augmentation de volume gingival", "تضخم حجم اللثة"}),
        Map.entry("gingival_hyperplasia", new String[] {"Gingival hyperplasia", "Hyperplasie gingivale", "فرط تنسج اللثة"}),
        Map.entry("clinical_attachment_loss", new String[] {"Clinical attachment loss", "Perte d’attache clinique", "فقدان الارتباط السريري"}),
        Map.entry("periodontitis", new String[] {"Periodontitis", "Parodontite", "التهاب دواعم السن"}),
        Map.entry("gingival_abscess", new String[] {"Gingival abscess", "Abcès gingival", "خراج لثوي"}),
        Map.entry("periodontal_abscess", new String[] {"Periodontal abscess", "Abcès parodontal", "خراج حول السنّ"}),
        Map.entry("gingival_ulceration", new String[] {"Gingival ulceration", "Ulcération gingivale", "تقرّح لثوي"}),
        Map.entry("gingival_lesion", new String[] {"Gingival lesion", "Lésion gingivale", "آفة لثوية"}),
        Map.entry("gingival_pigmentation", new String[] {"Gingival pigmentation", "Pigmentation gingivale", "تصبّغ اللثة"}),
        Map.entry("furcation_involvement", new String[] {"Furcation involvement", "Atteinte de furcation", "إصابة المفترق الجذري"}),
        Map.entry("suppuration", new String[] {"Suppuration", "Suppuration", "تقيّح"}),
        Map.entry("mucogingival_anomaly", new String[] {"Mucogingival anomaly", "Anomalie mucogingivale", "شذوذ لثوي مخاطي"}),
        Map.entry("peri_implant_mucositis", new String[] {"Peri-implant mucositis", "Mucosite péri-implantaire", "التهاب مخاطية حول الزرعة"}),
        Map.entry("peri_implantitis", new String[] {"Peri-implantitis", "Péri-implantite", "التهاب حول الزرعة"}),
        Map.entry("calculus", new String[] {"Calculus (tartar)", "Tartre", "جير سني"}),
        Map.entry("plaque", new String[] {"Dental plaque accumulation", "Accumulation de plaque dentaire", "لويحة سنية"}),
        Map.entry("crowding", new String[] {"Dental crowding", "Encombrement dentaire", "ازدحام الأسنان"}),
        Map.entry("spacing", new String[] {"Spacing / diastema", "Espacement / diastème", "تباعد الأسنان / ديستيما"}),
        Map.entry("rotation", new String[] {"Rotation", "Rotation", "دوران السن"}),
        Map.entry("tilting", new String[] {"Version / tilting", "Version / inclinaison", "ميلان السن"}),
        Map.entry("buccal_displacement", new String[] {"Buccal displacement", "Déplacement vestibulaire", "انزياح دهليزي"}),
        Map.entry("lingual_displacement", new String[] {"Lingual / palatal displacement", "Déplacement lingual / palatin", "انزياح لساني / حنكي"}),
        Map.entry("displacement", new String[] {"Tooth displacement", "Déplacement dentaire", "إزاحة السن"}),
        Map.entry("intrusion", new String[] {"Intrusion", "Intrusion", "انغراس السن"}),
        Map.entry("extrusion", new String[] {"Egression / extrusion", "Égression / extrusion", "بزوغ زائد للسن"}),
        Map.entry("mesial_migration", new String[] {"Mesial migration", "Migration mésiale", "هجرة إنسية"}),
        Map.entry("distal_migration", new String[] {"Distal migration", "Migration distale", "هجرة وحشية"}),
        Map.entry("crossbite", new String[] {"Crossbite", "Articulé croisé", "عضة متصالبة"}),
        Map.entry("open_bite", new String[] {"Open bite", "Béance", "عضة مفتوحة"}),
        Map.entry("deep_bite", new String[] {"Deep bite", "Supraclusion / occlusion profonde", "عضة عميقة"}),
        Map.entry("traumatic_occlusion", new String[] {"Traumatic occlusion", "Occlusion traumatique", "إطباق رضّي"}),
        Map.entry("occlusal_interference", new String[] {"Occlusal interference", "Interférence occlusale", "تداخل إطباقي"}),
        Map.entry("tooth_migration", new String[] {"Tooth migration", "Migration dentaire", "هجرة الأسنان"}),
        Map.entry("concussion", new String[] {"Concussion", "Contusion dentaire (concussion)", "رضّ السن"}),
        Map.entry("subluxation", new String[] {"Subluxation", "Subluxation", "خلع جزئي"}),
        Map.entry("lateral_luxation", new String[] {"Lateral luxation", "Luxation latérale", "خلع جانبي"}),
        Map.entry("extrusive_luxation", new String[] {"Extrusive luxation", "Luxation extrusive", "خلع بروزي"}),
        Map.entry("intrusive_luxation", new String[] {"Intrusive luxation", "Luxation intrusive", "خلع غرزي"}),
        Map.entry("enamel_fracture", new String[] {"Enamel fracture", "Fracture amélaire", "كسر المينا"}),
        Map.entry("soft_tissue_trauma", new String[] {"Soft tissue trauma", "Traumatisme des tissus mous", "رضّ الأنسجة الرخوة"}),
        Map.entry("ankylosis", new String[] {"Ankylosis", "Ankylose", "التحام السن بالعظم"}),
        Map.entry("percussion_sensitivity", new String[] {"Percussion sensitivity", "Sensibilité à la percussion", "حساسية للقرع"})
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
        Map.entry("suspected", new String[] {"suspected", "suspecté", "مشتبه به"}),
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
        Map.entry("signature", new String[] {"Practitioner’s stamp and signature", "Cachet et signature du praticien", "ختم وتوقيع الطبيب"}),
        Map.entry("chart", new String[] {"Dental chart", "Schéma dentaire", "مخطط الأسنان"}),
        Map.entry("chartIntro", new String[] {
            "The teeth seen from the biting side, with the patient’s right on the left. The letters beside a tooth refer to the legend.",
            "Les dents vues côté mastication, la droite du patient à gauche. Les lettres à côté d’une dent renvoient à la légende.",
            "الأسنان من جهة المضغ، ويمين المريض على اليسار. تشير الأحرف بجانب السن إلى مفتاح الرموز."}),
        Map.entry("chartLegend", new String[] {"Legend", "Légende", "مفتاح الرموز"}),
        Map.entry("chartNothing", new String[] {"Nothing recorded on the chart yet.", "Rien d’enregistré sur le schéma pour l’instant.", "لم يُسجَّل شيء على المخطط بعد."}),
        Map.entry("chartImageAlt", new String[] {"The patient’s dental chart. Every tooth and treatment marked on it is listed in the legend below.",
                "Schéma dentaire du patient. Chaque dent et chaque soin qui y sont marqués figurent dans la légende ci-dessous.",
                "المخطط السني للمريض. كل سن وكل علاج مُعلَّم عليه مذكور في المفتاح أدناه."}),
        Map.entry("patientRight", new String[] {"Patient’s right", "Droite du patient", "يمين المريض"}),
        Map.entry("patientLeft", new String[] {"Patient’s left", "Gauche du patient", "يسار المريض"}),
        Map.entry("legendKey", new String[] {"Key", "Repère", "الرمز"}),
        Map.entry("legendWhat", new String[] {"What", "Quoi", "ماذا"}),
        Map.entry("legendWhere", new String[] {"Teeth, date and who", "Dents, date et praticien", "الأسنان والتاريخ والطبيب"}),
        Map.entry("wholeMouth", new String[] {"Whole mouth", "Toute la bouche", "الفم كاملًا"}),
        Map.entry("inProgress", new String[] {"in progress", "en cours", "قيد الإنجاز"}),
        Map.entry("groupDONE", new String[] {"Treatments administered", "Soins réalisés", "العلاجات المنجزة"}),
        Map.entry("groupCONDITION", new String[] {"Conditions", "Pathologies", "الحالات المرضية"}),
        Map.entry("groupPLANNED", new String[] {"Work still to do", "Soins restant à réaliser", "علاجات متبقية"}),
        Map.entry("groupWATCH", new String[] {"To watch", "À surveiller", "للمراقبة"}),
        Map.entry("groupABSENT", new String[] {"Absent teeth", "Dents absentes", "الأسنان المفقودة"})
    );

    /** A finding that said work was owed, once the work is done: key → {en, fr, ar}. */
    private static final Map<String, String[]> DONE = Map.ofEntries(
        Map.entry("filling_required", new String[] {"Filling done", "Obturation réalisée", "حشوة منجزة"}),
        Map.entry("sealant_required", new String[] {"Sealant placed", "Scellement réalisé", "سدّ الشقوق منجز"}),
        Map.entry("restoration_required", new String[] {"Restoration done", "Restauration réalisée", "ترميم منجز"}),
        Map.entry("crown_required", new String[] {"Crown placed", "Couronne posée", "تاج مركَّب"}),
        Map.entry("crown_replacement_required", new String[] {"Crown replaced", "Couronne remplacée", "تاج مستبدل"}),
        Map.entry("root_canal_required", new String[] {"Root canal done", "Dévitalisation réalisée", "علاج عصب منجز"}),
        Map.entry("extraction_required", new String[] {"Extraction done", "Extraction réalisée", "خلع منجز"}),
        Map.entry("implant_required", new String[] {"Implant placed", "Implant posé", "زرعة مركَّبة"}),
        Map.entry("bridge_required", new String[] {"Bridge placed", "Bridge posé", "جسر مركَّب"}),
        Map.entry("veneer_required", new String[] {"Veneer placed", "Facette posée", "قشرة مركَّبة"}),
        Map.entry("scaling_required", new String[] {"Scaling done", "Détartrage réalisé", "تقليح منجز"}),
        Map.entry("periodontal_treatment_required", new String[] {"Periodontal treatment done", "Traitement parodontal réalisé", "علاج لثوي منجز"}),
        Map.entry("retreatment_required", new String[] {"Endodontic retreatment done", "Retraitement endodontique réalisé", "إعادة العلاج اللبي منجزة"})
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

    /** A finding that has been treated: what was owed, now done, or what was wrong, now treated. */
    String treated(String code) {
        String[] row = DONE.get(code);
        return row != null ? row[index] : finding(code) + " — " + text("treated");
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
                case "mesialroot" -> "Rm";
                case "distalroot" -> "Rd";
                case "buccalroot" -> "Rb";
                case "lingualroot" -> "Rl";
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
