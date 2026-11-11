package com.orthoflow.consultation.infrastructure.extraction;

import com.orthoflow.consultation.infrastructure.extraction.ConsultationPromptBuilder.CatalogEntry;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A plan line read from the conversation, matched to the clinic's catalogue
 * without a model: the tail reading is not shown the catalogue (it would cost
 * every few seconds), so its plan lines get their act, code and price here. The
 * full reading may still override with the code it chose.
 *
 * <p>Equal names first, after normalisation; then an act whose every word (of
 * three letters or more) is in the label — "un détartrage complet" is
 * "Détartrage", "composite deux faces" is not "Composite trois faces".
 */
public final class CatalogMatcher {

    private CatalogMatcher() {
    }

    public static CatalogEntry match(String label, List<CatalogEntry> catalog) {
        if (label == null || catalog == null || catalog.isEmpty()) return null;
        String wanted = TranscriptText.normalize(label);
        if (wanted.isEmpty()) return null;
        for (CatalogEntry entry : catalog) {
            if (entry.name() != null && TranscriptText.normalize(entry.name()).equals(wanted)) return entry;
        }
        Set<String> said = Arrays.stream(wanted.split(" ")).collect(Collectors.toSet());
        CatalogEntry best = null;
        int bestWords = 0;
        for (CatalogEntry entry : catalog) {
            if (entry.name() == null) continue;
            List<String> needed = Arrays.stream(TranscriptText.normalize(entry.name()).split(" "))
                    .filter(word -> word.length() > 2).toList();
            if (!needed.isEmpty() && said.containsAll(needed) && needed.size() > bestWords) {
                best = entry;
                bestWords = needed.size();
            }
        }
        return best;
    }
}
