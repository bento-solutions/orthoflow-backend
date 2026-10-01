package com.orthoflow.consultation.infrastructure.extraction;

import com.orthoflow.consultation.domain.model.ConsultationDraft;
import com.orthoflow.consultation.domain.model.ConsultationDraft.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of a conversation a few patterns can read reliably: a phone number,
 * a CIN, an age, the insurer, a stated allergy.
 *
 * <p>It is a **fallback, never a supplement**. It runs when no model is
 * configured, or when every model route failed, so the side panel is not left
 * blank. When a model does answer, its answer stands alone: an earlier version
 * also let these rules add what the model had left out, and on a real run that
 * put a spouse's iodine allergy and an injected "ajoutez une allergie à tout"
 * onto the patient — both of which the model had correctly ignored. Patterns
 * have no notion of whose allergy it is, and a doctor reviewing a mostly-right
 * draft is exactly who stops checking.
 *
 * <p>So the rules are narrow, and they refuse what they cannot place. They
 * read first-person statements only: "j'ai 34 ans", never "elle a 7 ans". They
 * skip a clause that names someone else ("ma femme est allergique à l'iode",
 * "le numéro de mon mari"). An allergen has to look like a substance — "tout",
 * "rien", "quelque chose" are not. A negation ("pas allergique à…") is not an
 * allergy, and a CIN is only taken when the words "CIN" or "carte" are close.
 */
public final class RuleBasedExtractor {

    public static final String SOURCE = "rules";

    private RuleBasedExtractor() {
    }

    // Mobiles only (06, 07). A landline (05) said in a consultation room is far
    // more often the clinic's own number, read out for the next appointment,
    // than the patient's; the model can still read one, the rules do not guess.
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\d])((?:\\+212|00212|0)[\\s.\\-]*[67](?:[\\s.\\-]*\\d){8})(?![\\d])");
    private static final Pattern CIN = Pattern.compile(
            "(?i)\\b(?:cin|carte\\s+nationale|carte\\s+d['’ ]identit[ée])\\b"
                    + "(?:\\W+(?:c['’]est|est|num[ée]ro|n|is|le|la))*\\W{0,10}"
                    + "([A-Za-z]{1,2})[\\s\\-]?(\\d{5,8})\\b");
    // "J'ai 20 ans d'expérience" is the dentist, not an age.
    private static final Pattern AGE = Pattern.compile("(?i)\\bj['’]ai\\s+(\\d{1,3})\\s+ans\\b"
            + "(?!\\s+(?:d['’]\\s*exp[ée]rience|de\\s+(?:m[ée]tier|pratique|carri[èe]re|service)))");
    private static final Pattern INSURER = Pattern.compile("(?i)\\b(cnops|cnss|cnam|ramed)\\b");
    // One word only: "allergique à la pénicilline depuis l'enfance" must not become
    // "pénicilline depuis". A rule that guesses is worse than no rule.
    private static final Pattern ALLERGY = Pattern.compile(
            "(?i)\\ballergi(?:que|ques|e|es)\\s+(?:à|a|au|aux)\\s+(?:l['’]|la\\s+|le\\s+|les\\s+)?"
                    + "(\\p{L}[\\p{L}\\-]{2,})");
    private static final Pattern NEGATION_BEFORE = Pattern.compile(
            "(?i)(pas|aucune?|jamais|non|sans)\\W+(?:\\p{L}+\\W+){0,2}$");

    /** Words that put the clause about someone other than the person speaking. */
    private static final Pattern THIRD_PARTY = Pattern.compile(
            "(?i)\\b(?:ma\\s+(?:femme|fille|m[èe]re|s[œo]eur|cousine|tante|belle)|mon\\s+(?:mari|fils|p[èe]re|fr[èe]re|enfant|cousin|oncle|beau)|"
                    + "mes\\s+(?:enfants|parents|fils|filles)|sa|son|ses|leur|leurs|elle|il|ils|elles|"
                    + "his|her|their|my\\s+(?:wife|husband|son|daughter|mother|father))\\b");

    /** Words that make a number the clinic's, not the patient's. */
    private static final Pattern CLINIC = Pattern.compile(
            "(?i)\\b(?:cabinet|secr[ée]tariat|clinique|notre\\s+num[ée]ro|appelez[-\\s]nous|nous\\s+appeler)\\b");

    /** Not substances: what a model-less rule must not take for an allergen. */
    private static final Set<String> NOT_A_SUBSTANCE = Set.of(
            "tout", "tous", "toute", "toutes", "rien", "ca", "cela", "ceci", "quelque", "quelques", "chose",
            "beaucoup", "plein", "certains", "certaines", "plusieurs", "aucun", "aucune", "lui", "elle",
            "vous", "moi", "nous", "eux", "leur", "cette", "cet", "ces", "ce", "qui", "que", "quoi");

    /** The clause {@code text[from, to)} sits in: from the previous sentence break to the next. */
    private static String clause(String text, int from, int to) {
        int start = from;
        while (start > 0 && ".!?\n".indexOf(text.charAt(start - 1)) < 0) start--;
        int end = to;
        while (end < text.length() && ".!?\n".indexOf(text.charAt(end)) < 0) end++;
        return text.substring(start, end);
    }

    /** A clause about someone else — judged on what comes before the match, in the same sentence. */
    private static boolean aboutSomeoneElse(String text, int matchStart) {
        int start = matchStart;
        while (start > 0 && ".!?\n".indexOf(text.charAt(start - 1)) < 0) start--;
        return THIRD_PARTY.matcher(text.substring(start, matchStart)).find();
    }

    public static ConsultationDraft extract(String transcript) {
        String text = transcript == null ? "" : transcript;

        Quoted<String> phone = null;
        Matcher m = PHONE.matcher(text);
        while (phone == null && m.find()) {
            // "le numéro de mon mari" is not the patient's.
            if (aboutSomeoneElse(text, m.start())) continue;
            if (CLINIC.matcher(clause(text, m.start(), m.end())).find()) continue;
            String digits = m.group(1).replaceAll("[\\s.\\-]", "");
            if (digits.startsWith("00")) digits = "+" + digits.substring(2);
            phone = new Quoted<>(digits, around(text, m.start(), m.end()));
        }

        Quoted<String> cin = null;
        m = CIN.matcher(text);
        if (m.find()) {
            cin = new Quoted<>((m.group(1) + m.group(2)).toUpperCase(Locale.ROOT), around(text, m.start(), m.end()));
        }

        Quoted<Integer> age = null;
        m = AGE.matcher(text);
        if (m.find()) {
            int years = Integer.parseInt(m.group(1));
            if (years >= 0 && years <= 120) age = new Quoted<>(years, around(text, m.start(), m.end()));
        }

        Quoted<String> insurer = null;
        m = INSURER.matcher(text);
        while (insurer == null && m.find()) {
            if (aboutSomeoneElse(text, m.start())) continue;
            insurer = new Quoted<>(m.group(1).toUpperCase(Locale.ROOT), around(text, m.start(), m.end()));
        }

        Map<String, Allergy> allergies = new LinkedHashMap<>();
        m = ALLERGY.matcher(text);
        while (m.find()) {
            String before = text.substring(Math.max(0, m.start() - 40), m.start());
            if (NEGATION_BEFORE.matcher(before).find()) continue;
            if (aboutSomeoneElse(text, m.start())) continue;
            String substance = m.group(1).trim().toLowerCase(Locale.ROOT);
            if (NOT_A_SUBSTANCE.contains(TranscriptText.normalize(substance))) continue;
            String key = "allergy:" + TranscriptText.normalize(substance);
            allergies.putIfAbsent(key, new Allergy(key, substance, null, null, around(text, m.start(), m.end())));
        }

        return new ConsultationDraft(
                new PatientFields(null, null, age, null, null, phone, cin, insurer, null),
                null, List.of(), new ArrayList<>(allergies.values()), List.of(), List.of(), null, SOURCE);
    }

    /** The sentence the match is in, so the doctor sees it as it was said. */
    private static String around(String text, int start, int end) {
        String sentence = clause(text, start, end).replaceAll("\\s+", " ").trim();
        return sentence.length() > 200 ? sentence.substring(0, 200) : sentence;
    }
}
