package com.orthoflow.export.application.dto;

import com.orthoflow.common.exception.IncompleteProfileException;

import java.util.ArrayList;
import java.util.List;

/**
 * The clinic's identity as it appears on a printed document: the header (logo, name, address,
 * contact) and the legal footer (legal name, ICE, IF, RC, Patente, CNSS, and the RIB on documents
 * that ask for payment).
 */
public record Letterhead(String name, String legalName, String ice, String taxId, String patente,
                         String rib, String address, String city, String phone, String email,
                         String logoDataUri, String rc, String cnss, String inpe, String website) {

    /** The name an empty profile starts with, which is not a clinic's real name. */
    public static final String PLACEHOLDER_NAME = "Cabinet";

    /** What a document needs from the profile before it may be issued. */
    public enum Use {
        /** Invoices, fee notes and care forms: a tax document needs the clinic's address and tax numbers. */
        FINANCIAL,
        /** Prescriptions: the pharmacist needs to know who prescribed and how to reach them. */
        PRESCRIPTION
    }

    /** The identity without the numbers added for the shared footer, as most tests build it. */
    public Letterhead(String name, String legalName, String ice, String taxId, String patente,
                      String rib, String address, String city, String phone, String email,
                      String logoDataUri) {
        this(name, legalName, ice, taxId, patente, rib, address, city, phone, email, logoDataUri,
                null, null, null, null);
    }

    public static Letterhead blank() {
        return new Letterhead("", null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * The profile fields a document of this use cannot be issued without, by their profile field name
     * (name, address, city, phone, ice, taxId), in the order the settings page shows them.
     */
    public List<String> missingFor(Use use) {
        List<String> missing = new ArrayList<>();
        if (blank(name) || PLACEHOLDER_NAME.equalsIgnoreCase(name.trim())) {
            missing.add("name");
        }
        if (use == Use.FINANCIAL) {
            if (blank(ice)) missing.add("ice");
            if (blank(taxId)) missing.add("taxId");
        }
        if (blank(address)) missing.add("address");
        if (blank(city)) missing.add("city");
        if (use == Use.PRESCRIPTION && blank(phone)) {
            missing.add("phone");
        }
        return missing;
    }

    /** Refuses, naming the missing fields, when the profile cannot carry a document of this use. */
    public Letterhead requireFor(Use use) {
        List<String> missing = missingFor(use);
        if (!missing.isEmpty()) {
            throw new IncompleteProfileException(missing);
        }
        return this;
    }

    /** The trading name and legal name differ, so the legal one is worth printing in the footer. */
    public boolean hasDistinctLegalName() {
        return !blank(legalName) && !legalName.trim().equalsIgnoreCase(name == null ? "" : name.trim());
    }

    /** "12 rue des Orangers, Casablanca", or null when the profile has neither. */
    public String addressLine() {
        return join(", ", address, city);
    }

    /** Phone, email and website, each kept whole when the header wraps them. */
    public List<String> contactParts() {
        return parts(phone, email, website);
    }

    /**
     * The legal line of the footer: the legal name (the trading name when there is no other), then
     * every registration number the profile holds, each with the label a Moroccan document uses.
     */
    public List<String> legalParts() {
        return parts(hasDistinctLegalName() ? legalName : name, labelled("ICE", ice), labelled("IF", taxId),
                labelled("RC", rc), labelled("Patente", patente), labelled("CNSS", cnss));
    }

    /** "RIB …" for documents that ask to be paid, or null. */
    public String ribLine() {
        return labelled("RIB", rib);
    }

    /**
     * Who signs the document, under the clinic's name in the header: the practitioner and their INPE,
     * or the clinic's own INPE when the document names no practitioner.
     */
    public String practitionerLine(String practitionerName, String practitionerInpe) {
        if (blank(practitionerName)) {
            return labelled("INPE", inpe);
        }
        return join(SEPARATOR, practitionerName, labelled("INPE", practitionerInpe));
    }

    private static final String SEPARATOR = " \u00b7 ";

    private static String labelled(String label, String value) {
        return blank(value) ? null : label + " " + value.trim();
    }

    private static List<String> parts(String... values) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (!blank(value)) out.add(value.trim());
        }
        return out;
    }

    private static String join(String separator, String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (blank(part)) continue;
            if (!out.isEmpty()) out.append(separator);
            out.append(part.trim());
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
