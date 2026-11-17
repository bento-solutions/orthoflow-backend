package com.orthoflow.common.exception;

import java.util.List;

/**
 * A legal document (invoice, fee note, care form, prescription) was asked for while the clinic
 * profile still lacks what such a document must carry. The response names the missing profile
 * fields so the app can send the person straight to them.
 */
public class IncompleteProfileException extends ConflictException {

    public static final String CODE = "CLINIC_PROFILE_INCOMPLETE";

    private final List<String> missing;

    public IncompleteProfileException(List<String> missing) {
        super("Complete the clinic profile before issuing this document. Missing: " + String.join(", ", missing) + ".");
        this.missing = List.copyOf(missing);
    }

    public List<String> missing() {
        return missing;
    }
}
