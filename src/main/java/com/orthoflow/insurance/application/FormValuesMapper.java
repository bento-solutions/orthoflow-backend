package com.orthoflow.insurance.application;

import com.orthoflow.insurance.application.dto.InsuranceFormDtos.FormData;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Line;
import com.orthoflow.insurance.infrastructure.forms.FormValues;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns what a form says ({@link FormData}) into the field names every layout shares.
 * Pure, so the wording of a form is tested without a PDF.
 */
public final class FormValuesMapper {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final DateTimeFormatter DIGITS = DateTimeFormatter.ofPattern("ddMMyyyy");

    private FormValuesMapper() {
    }

    public static FormValues map(FormData d) {
        Map<String, String> text = new LinkedHashMap<>();
        Set<String> checks = new LinkedHashSet<>();

        if (d.insured() != null) {
            put(text, "insured.fullName", d.insured().fullName());
            put(text, "insured.cin", d.insured().cin());
            put(text, "insured.immatriculation", d.insured().immatriculation());
            put(text, "insured.affiliation", d.insured().affiliation());
            put(text, "insured.address", d.insured().address());
            put(text, "insured.phone", d.insured().phone());
        }
        if (d.beneficiary() != null) {
            put(text, "beneficiary.fullName", d.beneficiary().fullName());
            put(text, "beneficiary.cin", d.beneficiary().cin());
            if (d.beneficiary().dateOfBirth() != null) {
                put(text, "beneficiary.dob", DAY.format(d.beneficiary().dateOfBirth()));
                put(text, "beneficiary.dobDigits", DIGITS.format(d.beneficiary().dateOfBirth()));
            }
            String sex = d.beneficiary().sex() == null ? null : d.beneficiary().sex().trim().toUpperCase(Locale.ROOT);
            if ("M".equals(sex) || "F".equals(sex)) {
                checks.add("sex." + sex);
            }
        }
        if (d.relation() != null) {
            checks.add("relation." + d.relation());
        }
        if (d.purpose() != null) {
            checks.add("purpose." + d.purpose().name());
        }
        put(text, "practitioner.name", d.practitionerName());
        put(text, "practitioner.inpe", d.practitionerInpe());
        put(text, "clinic.name", d.clinicName());
        put(text, "clinic.address", d.clinicAddress());
        put(text, "insurer.name", d.insurerName());
        put(text, "agreement.number", d.agreementNumber());
        put(text, "doctor.place", d.clinicCity());
        if (d.careDate() != null) {
            put(text, "doctor.date", DAY.format(d.careDate()));
            put(text, "doctor.dateDigits", DIGITS.format(d.careDate()));
            put(text, "care.date", DAY.format(d.careDate()));
        }
        if (d.total() != null && d.total().signum() > 0) {
            put(text, "total", money(d.total()));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (Line l : d.lines() == null ? List.<Line>of() : d.lines()) {
            Map<String, String> row = new LinkedHashMap<>();
            put(row, "teeth", teeth(l.teeth()));
            put(row, "code", l.code());
            put(row, "label", l.label());
            put(row, "date", l.date() == null ? null : SHORT_DAY.format(l.date()));
            put(row, "cotation", l.cotation());
            put(row, "amount", l.amount() == null ? null : money(l.amount()));
            rows.add(row);
            checks.add("care." + CareType.of(l.code()).name());
        }
        return new FormValues(text, checks, rows);
    }

    /** "1 250,00": the way an amount is written on a French form. */
    public static String money(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat("#,##0.00", symbols);
        return f.format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** "11, 21" or "11,21" → "11 21": the teeth as a form's narrow column takes them. */
    static String teeth(String teeth) {
        if (teeth == null) return null;
        String t = teeth.replaceAll("[,;/]+", " ").replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    /** "LASTNAME Firstname", the order the forms' "Nom et prénom" expects. */
    public static String formName(String firstName, String lastName) {
        String last = lastName == null ? "" : lastName.trim().toUpperCase(Locale.FRENCH);
        String first = firstName == null ? "" : firstName.trim();
        return (last + " " + first).trim();
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}
