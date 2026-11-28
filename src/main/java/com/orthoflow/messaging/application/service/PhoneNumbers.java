package com.orthoflow.messaging.application.service;

import java.util.regex.Pattern;

/** Turns what a receptionist or patient typed into international digits or strict E.164. */
public final class PhoneNumbers {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    private PhoneNumbers() {
    }

    /**
     * {@code 0661-123456}, {@code +212 661 123 456}, {@code 00212661123456} and
     * {@code 661123456} all become {@code 212661123456}. Returns null when the
     * result cannot be a phone number.
     */
    public static String toInternationalDigits(String raw, String defaultCountryCode) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        } else if (digits.startsWith("0") && digits.length() >= 9 && digits.length() <= 11) {
            digits = defaultCountryCode + digits.substring(1);
        } else if (!raw.trim().startsWith("+") && digits.length() == 9) {
            digits = defaultCountryCode + digits;
        }
        return digits.length() >= 10 && digits.length() <= 15 ? digits : null;
    }

    /**
     * Normalizes a user-provided phone number and optional country dial code (e.g. "+212")
     * into strict E.164 format (e.g. "+212661123456"), or null if invalid.
     */
    public static String normalizeE164(String raw, String countryCode, String defaultCountryCode) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        String candidate;
        if (trimmed.startsWith("+")) {
            candidate = "+" + digits;
        } else if (digits.startsWith("00")) {
            candidate = "+" + digits.substring(2);
        } else {
            String cc = countryCode != null && !countryCode.isBlank() ? countryCode : defaultCountryCode;
            String ccDigits = cc == null ? "" : cc.replaceAll("[^0-9]", "");
            if (ccDigits.isEmpty()) {
                return null;
            }
            if (digits.startsWith(ccDigits) && digits.length() >= ccDigits.length() + 8) {
                candidate = "+" + digits;
            } else {
                String subscriber = digits.startsWith("0") ? digits.substring(1) : digits;
                candidate = "+" + ccDigits + subscriber;
            }
        }
        return E164.matcher(candidate).matches() ? candidate : null;
    }
}
