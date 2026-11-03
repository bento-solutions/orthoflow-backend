package com.orthoflow.clinical.application.service;

import com.orthoflow.clinical.application.dto.ToothFindingResponse;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportChart;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportChartEntry;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportChartTooth;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportChartUse;
import com.orthoflow.treatment.domain.model.PatientTreatment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Works out the printed chart's legend and which of its letters each tooth carries.
 *
 * <p>A legend that names a colour leaves the reader to find the teeth; this one names
 * every kind of work administered and condition found, with its teeth, dates and who did
 * it, so it can be read on its own or against the drawing. What was administered comes
 * first because it is what the document is for.
 */
final class TreatmentChartBuilder {

    private static final List<String> GROUP_ORDER = List.of("DONE", "CONDITION", "PLANNED", "WATCH", "ABSENT");
    private static final Set<String> ABSENT_CODES = Set.of("extracted", "missing", "avulsion");

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private TreatmentChartBuilder() {}

    private record Key(String group, String code, String name, String outcome) {}

    private static final class Line {
        final String symbol;
        final String actCode;
        final List<PassportChartUse> uses = new ArrayList<>();

        Line(String symbol, String actCode) {
            this.symbol = symbol;
            this.actCode = actCode;
        }
    }

    static PassportChart build(String dentition, Map<String, String> statusByFdi,
                               List<ToothFindingResponse> history, List<PatientTreatment> treatments) {
        Map<Key, Line> lines = new LinkedHashMap<>();

        for (ToothFindingResponse f : history) {
            Key key = keyOf(f);
            if (key == null) continue;
            LocalDate date = "RESOLVED".equals(f.status()) && f.performedOn() == null && f.updatedAt() != null
                    ? f.updatedAt().toLocalDate() : f.performedOn();
            lines.computeIfAbsent(key, k -> new Line(symbolOf(f.findingCode(), f.kind(), k.group()), null))
                    .uses.add(new PassportChartUse(List.of(f.fdi()), surfaces(f.surface()), date, f.origin(), f.providerName()));
        }

        for (PatientTreatment t : treatments) {
            String outcome = switch (t.getStatus()) {
                case COMPLETED -> "COMPLETED";
                case ACTIVE -> "IN_PROGRESS";
                case PLANNED -> "PLANNED";
                default -> null;
            };
            if (outcome == null) continue;
            boolean done = "COMPLETED".equals(outcome);
            Key key = new Key(done ? "DONE" : "PLANNED", null, t.getTreatment().getName(), outcome);
            LocalDate date = done && t.getEndDate() != null ? t.getEndDate() : t.getStartDate();
            lines.computeIfAbsent(key, k -> new Line(done ? "ring" : "dashed", t.getTreatment().getActCode()))
                    .uses.add(new PassportChartUse(teethOf(t.getTeeth()), List.of(), date, "THIS_CLINIC", t.getDoctorName()));
        }

        List<Map.Entry<Key, Line>> ordered = new ArrayList<>(lines.entrySet());
        ordered.sort(Comparator
                .comparingInt((Map.Entry<Key, Line> e) -> GROUP_ORDER.indexOf(e.getKey().group()))
                // Findings before the clinic's own catalogue, then a fixed order so a reprint matches.
                .thenComparing(e -> e.getKey().code() == null ? 1 : 0)
                .thenComparing(e -> e.getKey().code() != null ? e.getKey().code() : e.getKey().name())
                .thenComparing(e -> e.getKey().outcome()));

        List<PassportChartEntry> legend = new ArrayList<>();
        Map<String, List<String>> keysByTooth = new TreeMap<>();
        Map<String, Boolean> done = new TreeMap<>();
        Map<String, Boolean> owed = new TreeMap<>();
        int index = 0;
        for (Map.Entry<Key, Line> e : ordered) {
            String letter = letter(index++);
            Key key = e.getKey();
            Line line = e.getValue();
            line.uses.sort(Comparator.comparing((PassportChartUse u) -> u.teeth().isEmpty() ? "" : u.teeth().get(0)));
            legend.add(new PassportChartEntry(letter, key.group(), line.symbol, key.code(), key.name(), line.actCode,
                    key.outcome(), List.copyOf(line.uses)));
            for (PassportChartUse use : line.uses) {
                for (String fdi : use.teeth()) {
                    List<String> keys = keysByTooth.computeIfAbsent(fdi, f -> new ArrayList<>());
                    if (!keys.contains(letter)) keys.add(letter);
                    if ("DONE".equals(key.group())) done.put(fdi, true);
                    if ("PLANNED".equals(key.group())) owed.put(fdi, true);
                }
            }
        }

        Map<String, PassportChartTooth> teeth = new TreeMap<>();
        keysByTooth.forEach((fdi, keys) -> teeth.put(fdi, new PassportChartTooth(fdi, statusByFdi.getOrDefault(fdi, "present"),
                List.copyOf(keys), done.getOrDefault(fdi, false), owed.getOrDefault(fdi, false))));
        // A status with no finding behind it, from a chart older than findings, is still a state of the tooth.
        statusByFdi.forEach((fdi, status) -> {
            if (!"present".equals(status)) teeth.putIfAbsent(fdi, new PassportChartTooth(fdi, status, List.of(), false, false));
        });
        return new PassportChart(dentition, List.copyOf(teeth.values()), legend);
    }

    /** Which line of the legend a finding belongs on, or null when it is not part of the record. */
    private static Key keyOf(ToothFindingResponse f) {
        if ("RESOLVED".equals(f.status())) return new Key("DONE", f.findingCode(), null, "TREATED");
        if (!"ACTIVE".equals(f.status())) return null;
        if (ABSENT_CODES.contains(f.findingCode())) return new Key("ABSENT", f.findingCode(), null, "ACTIVE");
        return switch (f.kind()) {
            case "EXISTING" -> new Key("DONE", f.findingCode(), null, "IN_PLACE");
            case "TREATMENT_REQUIRED" -> new Key("PLANNED", f.findingCode(), null, "REQUIRED");
            case "OBSERVATION" -> new Key("WATCH", f.findingCode(), null, "ACTIVE");
            default -> new Key("CONDITION", f.findingCode(), null, "ACTIVE");
        };
    }

    /** How the chart draws a code. The same geometry as the screen (frontend core/clinical/tooth-status.ts). */
    static String symbolOf(String code, String kind, String group) {
        if ("PLANNED".equals(group)) return "dashed";
        if ("ABSENT".equals(group)) return "cross";
        if ("WATCH".equals(group)) return "dot";
        // A condition since treated is no longer that condition: the tooth just carries the work's ring.
        if ("DONE".equals(group) && "CONDITION".equals(kind)) return "ring";
        return switch (code) {
            case "existing_crown", "existing_bridge", "existing_veneer", "existing_implant", "crown_required",
                 "crown_replacement_required", "bridge_required", "veneer_required", "implant_required",
                 "crown_defective" -> "hatch";
            case "existing_root_canal", "existing_post", "root_canal_required" -> "endo";
            case "existing_filling", "existing_composite", "existing_amalgam", "existing_inlay", "existing_sealant",
                 "filling_required", "restoration_required", "sealant_required" -> "solid";
            case "existing_deciduous" -> "dotted";
            case "caries", "recurrent_caries", "deep_caries", "cavity" -> "stipple";
            case "fracture", "retained_root", "extensive_destruction" -> "jag";
            case "impacted" -> "bars";
            case "extraction_required" -> "cross";
            default -> "DONE".equals(group) ? "ring" : "dot";
        };
    }

    /** A, B … Z, then AA, AB: a mouth with more than 26 kinds of thing recorded still gets a letter for each. */
    static String letter(int index) {
        String letter = String.valueOf(ALPHABET.charAt(index % ALPHABET.length()));
        return index < ALPHABET.length() ? letter : letter(index / ALPHABET.length() - 1) + letter;
    }

    private static List<String> surfaces(String surface) {
        return surface == null || surface.isBlank() ? List.of() : Arrays.asList(surface.split("-"));
    }

    private static List<String> teethOf(String teeth) {
        return teeth == null || teeth.isBlank() ? List.of()
                : Arrays.stream(teeth.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
