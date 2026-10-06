package com.orthoflow.help.application.service;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Keyword matching for a few dozen short notes. A vector index would be more than the
 * problem needs: the corpus is small, the vocabulary is the application's own, and
 * every result can be explained by the words that matched.
 */
final class HelpSearch {

    private static final Set<String> STOP = Set.of(
            // French
            "comment", "quel", "quelle", "quels", "quelles", "pour", "dans", "avec", "sans", "est", "sont", "les", "des", "une", "que", "qui",
            "peut", "puis", "faire", "fait", "cette", "cet", "ces", "sur", "par", "pas", "vous", "nous", "mon", "mes", "son", "ses", "aux",
            // English
            "how", "what", "which", "can", "the", "and", "for", "with", "from", "are", "does", "this", "that", "where", "when", "why", "you",
            "your", "was", "has", "have", "into", "not", "its");

    private HelpSearch() {
    }

    /** Lower-cased, accent-stripped words of three letters or more, minus the stop words. Arabic is kept as written. */
    static Set<String> terms(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String word : normalise(text).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !STOP.contains(word)) {
                out.add(word);
            }
        }
        return out;
    }

    /** Title hits count three times a body hit; each term counts once however often it repeats. */
    static int score(Set<String> terms, String title, String body) {
        String t = normalise(title);
        String b = normalise(body);
        int score = 0;
        for (String term : terms) {
            if (t.contains(term)) {
                score += 3;
            }
            if (b.contains(term)) {
                score += 1;
            }
        }
        return score;
    }

    private static String normalise(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
