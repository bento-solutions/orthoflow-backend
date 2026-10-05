package com.orthoflow.messaging.application.service;

/** Turns what a receptionist typed into the digits-only international form the WhatsApp bridge expects. */
public final class PhoneNumbers {

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
}
