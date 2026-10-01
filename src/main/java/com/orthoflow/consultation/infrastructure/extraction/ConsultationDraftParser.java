package com.orthoflow.consultation.infrastructure.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.consultation.domain.model.ConsultationDraft;
import com.orthoflow.consultation.domain.model.ConsultationDraft.*;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationPromptBuilder.CatalogEntry;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns what a model answered into a {@link ConsultationDraft} — and treats the
 * answer as untrusted.
 *
 * <p>The prompt asks a model to be faithful; this is where that is enforced.
 * Nothing is kept unless it passes all of:
 * <ul>
 *   <li>its {@code quote} is really in the transcript (a quote that is not is a
 *       claim about some other conversation), and is evidence for this value — a
 *       quote under three words must contain it, and digits in a phone number,
 *       CIN or age must be the quote's;</li>
 *   <li>a price marked as spoken is a number actually said in the conversation;</li>
 *   <li>its value is the right kind of value — a phone number has digits, a CIN
 *       has the shape of a CIN, a category is one the record has;</li>
 *   <li>a treatment code names a treatment the clinic actually has.</li>
 * </ul>
 * What fails is dropped rather than repaired. The doctor reviews the draft, but
 * a draft that is mostly right and occasionally invented is the dangerous kind:
 * it trains people to tick through it.
 */
@Slf4j
public final class ConsultationDraftParser {

    /** The draft, and how many of the model's claims were thrown out getting to it. */
    public record Result(ConsultationDraft draft, int dropped) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> HISTORY_CATEGORIES =
            Set.of("CONDITION", "MEDICATION", "SURGERY", "DENTAL_HISTORY", "FAMILY", "LIFESTYLE", "OTHER");
    private static final Set<String> TREATMENT_TYPES = Set.of("MEDICATION", "DENTAL", "OTHER");
    private static final Set<String> SEVERITIES = Set.of("MILD", "MODERATE", "SEVERE");
    private static final Set<String> INSURERS = Set.of("CNOPS", "CNSS", "CNAM", "RAMED", "PRIVATE");

    private static final Pattern PHONE = Pattern.compile("^\\+?\\d{8,15}$");
    private static final Pattern CIN = Pattern.compile("^[A-Z]{1,2}\\d{4,8}$");
    private static final Pattern NAME = Pattern.compile("^\\p{L}[\\p{L} '.\\-]{0,98}$");
    private static final Pattern FDI_LIST = Pattern.compile("^[1-8][1-8](,[1-8][1-8])*$");

    private static final int MAX_QUOTE = 200;
    private static final int MAX_LABEL = 160;
    private static final int MAX_DETAIL = 400;
    private static final int MAX_ITEMS = 40;
    private static final long MAX_APPOINTMENT_DAYS = 730;
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000");

    private final String normalizedTranscript;
    private final List<CatalogEntry> catalog;
    private final LocalDate today;
    private int dropped;

    private ConsultationDraftParser(String transcript, List<CatalogEntry> catalog, LocalDate today) {
        this.normalizedTranscript = TranscriptText.normalize(transcript);
        this.catalog = catalog == null ? List.of() : catalog;
        this.today = today;
    }

    /**
     * @return the validated draft, or null when {@code modelText} is not a JSON
     *         object, or leaves out one of the sections — the caller treats
     *         either as the route having failed
     */
    public static Result parse(String modelText, String transcript, List<CatalogEntry> catalog,
                               LocalDate today, String source) {
        JsonNode root = readObject(modelText);
        if (root == null) return null;
        // "Nothing said about the plan" is an empty list or null. A section that is
        // not there at all is a model that stopped early: on a real run a reasoning
        // model returned valid JSON that simply ended after the medical history,
        // and the consultation's whole treatment plan and next appointment were
        // silently absent. Absence is not an answer; the next route gets a turn.
        for (String section : SECTIONS) {
            if (!root.has(section)) {
                log.warn("Consultation extraction: the answer has no '{}' section — treating the route as failed", section);
                return null;
            }
        }
        return new ConsultationDraftParser(transcript, catalog, today).read(root, source);
    }

    /** Every top-level key the prompt asks for. */
    static final List<String> SECTIONS = List.of(
            "patient", "chiefComplaint", "activeTreatments", "allergies", "medicalHistory",
            "treatmentPlan", "nextAppointment");

    private static JsonNode readObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = JSON.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Result read(JsonNode root, String source) {
        JsonNode p = root.path("patient");
        PatientFields patient = new PatientFields(
                name(p.path("firstName")),
                name(p.path("lastName")),
                age(p.path("age")),
                dateOfBirth(p.path("dateOfBirth")),
                gender(p.path("gender")),
                phone(p.path("phone")),
                cin(p.path("cin")),
                insurer(p.path("insuranceProvider")),
                digitsChecked(text(p.path("insuranceNumber"), 40, "[A-Za-z0-9 /.\\-]+")));

        ConsultationDraft draft = new ConsultationDraft(
                patient,
                text(root.path("chiefComplaint"), MAX_DETAIL, null),
                activeTreatments(root.path("activeTreatments")),
                allergies(root.path("allergies")),
                history(root.path("medicalHistory")),
                plan(root.path("treatmentPlan")),
                nextAppointment(root.path("nextAppointment")),
                source);
        if (dropped > 0) {
            log.info("Consultation extraction: dropped {} claim(s) that failed validation", dropped);
        }
        return new Result(draft, dropped);
    }

    // ── Patient fields ──────────────────────────────────────────────────

    /** A {value, quote} pair whose quote is in the transcript; null otherwise. */
    private Quoted<String> quoted(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.path("value").asText("").trim();
        String quote = node.path("quote").asText("").trim();
        if (value.isEmpty()) return null;
        if (!TranscriptText.isSupported(quote, normalizedTranscript) || !TranscriptText.backs(quote, value)) {
            dropped++;
            return null;
        }
        return new Quoted<>(value, clip(quote, MAX_QUOTE));
    }

    /** {@link #quoted}, and its digits are the quote's. */
    private Quoted<String> numbered(JsonNode node) {
        Quoted<String> q = quoted(node);
        if (q == null) return null;
        if (!TranscriptText.digitsMatch(q.quote(), q.value())) {
            dropped++;
            return null;
        }
        return q;
    }

    private Quoted<String> name(JsonNode node) {
        Quoted<String> q = quoted(node);
        if (q == null) return null;
        String value = q.value().replaceAll("\\s+", " ");
        if (!NAME.matcher(value).matches()) {
            dropped++;
            return null;
        }
        return new Quoted<>(value, q.quote());
    }

    private Quoted<Integer> age(JsonNode node) {
        Quoted<String> q = numbered(node);
        if (q == null) return null;
        try {
            int years = Integer.parseInt(q.value().replaceAll("[^0-9]", ""));
            if (years < 0 || years > 120) throw new NumberFormatException();
            return new Quoted<>(years, q.quote());
        } catch (NumberFormatException e) {
            dropped++;
            return null;
        }
    }

    private Quoted<String> dateOfBirth(JsonNode node) {
        Quoted<String> q = quoted(node);
        if (q == null) return null;
        try {
            LocalDate date = LocalDate.parse(q.value());
            if (!date.isBefore(today) || date.isBefore(today.minusYears(121))) throw new DateTimeParseException("", "", 0);
            return new Quoted<>(date.toString(), q.quote());
        } catch (DateTimeParseException e) {
            dropped++;
            return null;
        }
    }

    private Quoted<String> gender(JsonNode node) {
        Quoted<String> q = quoted(node);
        if (q == null) return null;
        String value = q.value().toUpperCase(Locale.ROOT);
        if (!value.equals("M") && !value.equals("F")) {
            dropped++;
            return null;
        }
        return new Quoted<>(value, q.quote());
    }

    private Quoted<String> phone(JsonNode node) {
        Quoted<String> q = numbered(node);
        if (q == null) return null;
        String digits = q.value().replaceAll("[\\s.\\-()]", "");
        if (digits.startsWith("00")) digits = "+" + digits.substring(2);
        if (!PHONE.matcher(digits).matches()) {
            dropped++;
            return null;
        }
        return new Quoted<>(digits, q.quote());
    }

    private Quoted<String> cin(JsonNode node) {
        Quoted<String> q = numbered(node);
        if (q == null) return null;
        String value = q.value().replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
        if (!CIN.matcher(value).matches()) {
            dropped++;
            return null;
        }
        return new Quoted<>(value, q.quote());
    }

    private Quoted<String> insurer(JsonNode node) {
        Quoted<String> q = quoted(node);
        if (q == null) return null;
        String value = q.value().toUpperCase(Locale.ROOT);
        if (!INSURERS.contains(value)) {
            dropped++;
            return null;
        }
        return new Quoted<>(value, q.quote());
    }

    /** Free text of at most {@code max} characters, optionally restricted to a character class. */
    private Quoted<String> text(JsonNode node, int max, String allowed) {
        Quoted<String> q = quoted(node);
        if (q == null) return null;
        String value = q.value().replaceAll("\\s+", " ");
        if (value.length() > max || (allowed != null && !value.matches(allowed))) {
            dropped++;
            return null;
        }
        return new Quoted<>(value, q.quote());
    }

    private Quoted<String> digitsChecked(Quoted<String> q) {
        if (q == null || TranscriptText.digitsMatch(q.quote(), q.value())) return q;
        dropped++;
        return null;
    }

    // ── Lists ───────────────────────────────────────────────────────────

    private List<ActiveTreatment> activeTreatments(JsonNode array) {
        Map<String, ActiveTreatment> byKey = new LinkedHashMap<>();
        for (JsonNode item : items(array)) {
            String label = label(item.path("label"));
            String quote = supportedQuote(item, label);
            if (label == null || quote == null) { dropped++; continue; }
            String type = enumOr(item.path("type"), TREATMENT_TYPES, "OTHER");
            byKey.putIfAbsent("treatment:" + TranscriptText.normalize(label),
                    new ActiveTreatment("treatment:" + TranscriptText.normalize(label), label,
                            detail(item.path("detail")), type, quote));
        }
        return List.copyOf(byKey.values());
    }

    private List<Allergy> allergies(JsonNode array) {
        Map<String, Allergy> byKey = new LinkedHashMap<>();
        for (JsonNode item : items(array)) {
            String substance = label(item.path("substance"));
            String quote = supportedQuote(item, substance);
            if (substance == null || quote == null) { dropped++; continue; }
            String severity = enumOr(item.path("severity"), SEVERITIES, null);
            String key = "allergy:" + TranscriptText.normalize(substance);
            byKey.putIfAbsent(key, new Allergy(key, substance, detail(item.path("reaction")), severity, quote));
        }
        return List.copyOf(byKey.values());
    }

    private List<HistoryEntry> history(JsonNode array) {
        Map<String, HistoryEntry> byKey = new LinkedHashMap<>();
        for (JsonNode item : items(array)) {
            String label = label(item.path("label"));
            String quote = supportedQuote(item, label);
            if (label == null || quote == null) { dropped++; continue; }
            String category = enumOr(item.path("category"), HISTORY_CATEGORIES, "OTHER");
            String key = "history:" + category + ":" + TranscriptText.normalize(label);
            byKey.putIfAbsent(key, new HistoryEntry(key, category, label, detail(item.path("detail")), quote));
        }
        return List.copyOf(byKey.values());
    }

    private List<PlanItem> plan(JsonNode array) {
        Map<String, PlanItem> byKey = new LinkedHashMap<>();
        for (JsonNode item : items(array)) {
            String label = label(item.path("label"));
            String quote = supportedQuote(item, label);
            if (label == null || quote == null) { dropped++; continue; }

            CatalogEntry match = catalogMatch(item.path("treatmentCode").asText(""));
            String teeth = teeth(item.path("teeth").asText(""));
            // "Spoken" is a claim too: a price nobody said is the model's guess,
            // often the catalogue's, and must not be shown as the doctor's word.
            BigDecimal spoken = price(item.path("price"));
            if (spoken != null && !TranscriptText.saysAmount(normalizedTranscript, spoken)) {
                spoken = null;
                dropped++;
            }
            BigDecimal price = spoken != null ? spoken : match == null ? null : match.basePrice();
            String priceSource = spoken != null ? "SPOKEN" : price != null ? "CATALOG" : null;

            String key = "plan:" + TranscriptText.normalize(label) + ":" + (teeth == null ? "" : teeth);
            byKey.putIfAbsent(key, new PlanItem(key, label,
                    match == null ? null : match.id(), match == null ? null : match.code(),
                    teeth, price, priceSource, detail(item.path("notes")), quote));
        }
        return List.copyOf(byKey.values());
    }

    private NextAppointment nextAppointment(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String quote = supportedQuote(node);
        if (quote == null) {
            if (!node.isEmpty()) dropped++;
            return null;
        }
        LocalDate date = null;
        String rawDate = node.path("date").asText("");
        if (!rawDate.isBlank()) {
            try {
                LocalDate parsed = LocalDate.parse(rawDate.trim());
                if (!parsed.isBefore(today) && !parsed.isAfter(today.plusDays(MAX_APPOINTMENT_DAYS))) date = parsed;
            } catch (DateTimeParseException ignored) {
                // falls through to inDays
            }
        }
        Integer inDays = null;
        if (node.path("inDays").canConvertToInt()) {
            int days = node.path("inDays").asInt();
            if (days >= 0 && days <= MAX_APPOINTMENT_DAYS) inDays = days;
        }
        if (date == null && inDays != null) date = today.plusDays(inDays);

        String time = null;
        String rawTime = node.path("time").asText("");
        if (!rawTime.isBlank()) {
            try {
                time = LocalTime.parse(rawTime.trim()).withSecond(0).withNano(0).toString();
            } catch (DateTimeParseException ignored) {
                // an unreadable time is left for the doctor to set
            }
        }
        String reason = detail(node.path("reason"));
        if (date == null && time == null && reason == null) {
            dropped++;
            return null;
        }
        return new NextAppointment(date == null ? null : date.toString(), time, inDays, reason, quote);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static Iterable<JsonNode> items(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (item.isObject() && out.size() < MAX_ITEMS) out.add(item);
            }
        }
        return out;
    }

    /** The item's quote, if it is in the transcript. */
    private String supportedQuote(JsonNode item) {
        String quote = item.path("quote").asText("").trim();
        return TranscriptText.isSupported(quote, normalizedTranscript) ? clip(quote, MAX_QUOTE) : null;
    }

    /** The item's quote, if it is in the transcript and is evidence for {@code value}. */
    private String supportedQuote(JsonNode item, String value) {
        String quote = supportedQuote(item);
        return quote != null && value != null && TranscriptText.backs(quote, value) ? quote : null;
    }

    private static String label(JsonNode node) {
        String value = node.asText("").replaceAll("\\s+", " ").trim();
        return value.isEmpty() || value.length() > MAX_LABEL ? null : value;
    }

    private static String detail(JsonNode node) {
        String value = node.asText("").replaceAll("\\s+", " ").trim();
        return value.isEmpty() ? null : clip(value, MAX_DETAIL);
    }

    private static String enumOr(JsonNode node, Set<String> allowed, String fallback) {
        String value = node.asText("").trim().toUpperCase(Locale.ROOT);
        return allowed.contains(value) ? value : fallback;
    }

    private static String teeth(String raw) {
        String cleaned = raw.replaceAll("[\\s;/]+", ",").replaceAll("^,|,$", "");
        return FDI_LIST.matcher(cleaned).matches() ? cleaned : null;
    }

    private static BigDecimal price(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) return null;
        BigDecimal value = node.decimalValue();
        return value.signum() < 0 || value.compareTo(MAX_PRICE) > 0 ? null : value;
    }

    private CatalogEntry catalogMatch(String code) {
        if (code == null || code.isBlank()) return null;
        for (CatalogEntry entry : catalog) {
            if (entry.code().equalsIgnoreCase(code.trim())) return entry;
        }
        return null;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
