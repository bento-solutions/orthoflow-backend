package com.orthoflow.consultation.infrastructure.extraction;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;

/**
 * The one way text is compared in this package: accents gone, lower case,
 * everything that is not a letter or digit a single space.
 *
 * <p>A transcript and a model's quote of it differ in exactly the ways this
 * removes — punctuation the recogniser added, capitals, a dropped accent, a
 * doubled space — and in nothing that changes what was said.
 */
final class TranscriptText {

    private TranscriptText() {
    }

    static String normalize(String text) {
        if (text == null) return "";
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean gap = true;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
                gap = false;
            } else if (!gap) {
                out.append(' ');
                gap = true;
            }
        }
        return out.toString().trim().toLowerCase(Locale.ROOT);
    }

    /** Words of the transcript that may sit between two words of a quote without breaking it. */
    private static final int MAX_GAP = 2;

    /**
     * Whether {@code quote} really is something said in the conversation.
     *
     * <p>Exact after normalisation, on whole words (a fragment such as "an"
     * is not found inside "dans"), or — because a model sometimes drops a word
     * when it copies — a quote of four words or more whose words appear in the
     * transcript in order, with at most {@value #MAX_GAP} other words between
     * neighbours, and at least 85% of them found. Shorter quotes must match
     * exactly: a two-word quote that is "mostly there" is not evidence of
     * anything, and neither is one whose words are scattered across the
     * conversation.
     *
     * @param normalizedTranscript the transcript, already passed through {@link #normalize}
     */
    static boolean isSupported(String quote, String normalizedTranscript) {
        String q = normalize(quote);
        if (q.isEmpty() || normalizedTranscript.isEmpty()) return false;
        if ((" " + normalizedTranscript + " ").contains(" " + q + " ")) return true;

        String[] words = q.split(" ");
        if (words.length < 4) return false;
        String[] heard = normalizedTranscript.split(" ");
        int needed = (int) Math.ceil(words.length * 0.85);

        // Start from every place the quote's first (or, if that was the dropped
        // one, second) word occurs, and follow the quote forward.
        for (int first = 0; first < Math.min(2, words.length); first++) {
            for (int start = 0; start < heard.length; start++) {
                if (!heard[start].equals(words[first])) continue;
                int matched = 1;
                int at = start;
                for (int i = first + 1; i < words.length; i++) {
                    int found = -1;
                    for (int j = at + 1; j <= Math.min(heard.length - 1, at + 1 + MAX_GAP); j++) {
                        if (heard[j].equals(words[i])) {
                            found = j;
                            break;
                        }
                    }
                    if (found >= 0) {
                        matched++;
                        at = found;
                    }
                }
                if (matched >= needed && matched >= 4) return true;
            }
        }
        return false;
    }

    /** Below this many words a quote is not a sentence, only a word or two that could be anywhere. */
    private static final int SENTENCE_WORDS = 3;

    /**
     * Whether a real quote is evidence for this value, not just evidence that
     * something was said. A sentence is taken as context for what was read from
     * it. A shorter quote — "oui", "d'accord", a lone name — backs only the value
     * it contains: "oui" is in every conversation and proves no allergy.
     */
    static boolean backs(String quote, String value) {
        String q = normalize(quote);
        if (q.split(" ").length >= SENTENCE_WORDS) return true;
        String v = normalize(value);
        return !v.isEmpty() && (" " + q + " ").contains(" " + v + " ");
    }

    /**
     * Whether a value's digits are the ones in its quote. A phone number, a CIN
     * or an age the model wrote with digits the quote does not have is a digit
     * it invented or misread. A quote with no digits at all (a number said in
     * words) cannot be checked this way and passes.
     */
    static boolean digitsMatch(String quote, String value) {
        String inQuote = digits(quote);
        if (inQuote.isEmpty()) return true;
        String inValue = digits(value);
        if (inValue.isEmpty()) return false;
        if (inQuote.contains(inValue)) return true;
        // "+212 6…" in the value, "06…" said aloud: the same number.
        return inValue.startsWith("212") && inQuote.contains("0" + inValue.substring(3));
    }

    private static String digits(String text) {
        return text == null ? "" : text.replaceAll("[^0-9]", "");
    }

    /**
     * Whether {@code amount} is a number said somewhere in the conversation —
     * "300", "3500", or "3 500" with its thousands spaced out.
     *
     * @param normalizedTranscript the transcript, already passed through {@link #normalize}
     */
    static boolean saysAmount(String normalizedTranscript, BigDecimal amount) {
        if (amount == null || normalizedTranscript.isEmpty()) return false;
        String plain = amount.stripTrailingZeros().toPlainString();
        String[] parts = plain.split("\\.");
        String[] words = normalizedTranscript.split(" ");
        for (int i = 0; i < words.length; i++) {
            if (!words[i].chars().allMatch(Character::isDigit)) continue;
            // Join the groups of three that follow: "3 500" is 3500.
            StringBuilder number = new StringBuilder(words[i]);
            int j = i;
            while (true) {
                if (number.toString().equals(parts[0])) {
                    if (parts.length == 1) return true;
                    // "250,50" normalises to "250 50"
                    if (j + 1 < words.length && words[j + 1].replaceAll("0+$", "").equals(parts[1])) return true;
                }
                if (j + 1 < words.length && words[j + 1].length() == 3 && words[j + 1].chars().allMatch(Character::isDigit)) {
                    number.append(words[++j]);
                } else {
                    break;
                }
            }
        }
        return false;
    }
}
