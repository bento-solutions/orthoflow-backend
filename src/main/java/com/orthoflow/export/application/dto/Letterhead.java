package com.orthoflow.export.application.dto;

/** The clinic's identity as it appears at the top of a printed document. */
public record Letterhead(String name, String legalName, String ice, String taxId, String patente,
                         String rib, String address, String city, String phone, String email,
                         String logoDataUri) {

    public static Letterhead blank() {
        return new Letterhead("", null, null, null, null, null, null, null, null, null, null);
    }
}
