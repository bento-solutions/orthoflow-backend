package com.orthoflow.billing.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount written out in French, as a fee note states it: "arrêtée la présente
 * note à la somme de mille deux cent cinquante dirhams et cinquante centimes".
 * The agreement rules are the ones that trip people up — "quatre-vingts" takes
 * its s only at the end of a number, "cent" likewise, "mille" never does.
 */
public final class AmountInWords {

    private static final String[] UNITS = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize"};
    private static final String[] TENS = {"", "", "vingt", "trente", "quarante", "cinquante", "soixante"};

    private AmountInWords() {
    }

    /** {@code 1250.50, "dirham", "centime"} → "mille deux cent cinquante dirhams et cinquante centimes". */
    public static String french(BigDecimal amount, String unit, String subUnit) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP).abs();
        long whole = rounded.longValue();
        int cents = rounded.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        StringBuilder out = new StringBuilder(words(whole)).append(' ').append(unit);
        if (whole > 1) {
            out.append('s');
        }
        if (cents > 0) {
            out.append(" et ").append(words(cents)).append(' ').append(subUnit);
            if (cents > 1) {
                out.append('s');
            }
        }
        return out.toString();
    }

    public static String words(long n) {
        if (n < 0) {
            return "moins " + words(-n);
        }
        if (n == 0) {
            return "zéro";
        }
        StringBuilder out = new StringBuilder();
        long billions = n / 1_000_000_000L;
        long millions = (n / 1_000_000L) % 1000;
        long thousands = (n / 1000L) % 1000;
        long rest = n % 1000;
        if (billions > 0) {
            out.append(under1000(billions, false)).append(billions > 1 ? " milliards" : " milliard");
        }
        if (millions > 0) {
            join(out).append(under1000(millions, false)).append(millions > 1 ? " millions" : " million");
        }
        if (thousands > 0) {
            // "mille", not "un mille"; and invariant.
            join(out).append(thousands == 1 ? "mille" : under1000(thousands, false) + " mille");
        }
        if (rest > 0) {
            join(out).append(under1000(rest, true));
        }
        return out.toString();
    }

    private static StringBuilder join(StringBuilder out) {
        return out.length() == 0 ? out : out.append(' ');
    }

    /** {@code terminal}: nothing follows this group, so "vingt" and "cent" may take their plural s. */
    private static String under1000(long n, boolean terminal) {
        long hundreds = n / 100;
        long rest = n % 100;
        StringBuilder out = new StringBuilder();
        if (hundreds == 1) {
            out.append("cent");
        } else if (hundreds > 1) {
            out.append(UNITS[(int) hundreds]).append(" cent");
            if (rest == 0 && terminal) {
                out.append('s');
            }
        }
        if (rest > 0) {
            if (hundreds > 0) {
                out.append(' ');
            }
            out.append(under100(rest, terminal));
        }
        return out.toString();
    }

    private static String under100(long n, boolean terminal) {
        int v = (int) n;
        if (v < 17) {
            return UNITS[v];
        }
        if (v < 20) {
            return "dix-" + UNITS[v - 10];
        }
        if (v < 70) {
            int tens = v / 10;
            int unit = v % 10;
            if (unit == 0) {
                return TENS[tens];
            }
            return TENS[tens] + (unit == 1 ? " et " : "-") + UNITS[unit];
        }
        if (v < 80) {
            // soixante-dix .. soixante-dix-neuf, with "soixante et onze".
            return v == 71 ? "soixante et onze" : "soixante-" + under100(v - 60, terminal);
        }
        if (v == 80) {
            return terminal ? "quatre-vingts" : "quatre-vingt";
        }
        return "quatre-vingt-" + under100(v - 80, terminal);
    }
}
