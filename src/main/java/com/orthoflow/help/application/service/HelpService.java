package com.orthoflow.help.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.help.application.dto.HelpDtos.NoteView;
import com.orthoflow.help.domain.model.HelpNote;
import com.orthoflow.help.infrastructure.HelpNoteJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The help notes a clinic sees: its own text where it has changed a note, the built-in
 * text otherwise, in the language asked for, falling back to French, then English,
 * then Arabic when a note has not been translated.
 */
@Service
@RequiredArgsConstructor
public class HelpService {

    static final List<String> LANGUAGES = List.of("fr", "en", "ar");
    private static final Pattern PAGE_KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{0,59}$");

    private final HelpNoteJpaRepository notes;

    @Transactional(readOnly = true)
    public List<NoteView> all(UUID practiceId, String lang) {
        Map<String, List<HelpNote>> byPage = notes.findVisible(practiceId).stream().collect(Collectors.groupingBy(HelpNote::getPageKey));
        List<NoteView> out = new ArrayList<>();
        for (List<HelpNote> versions : byPage.values()) {
            pick(versions, language(lang)).ifPresent(out::add);
        }
        out.sort(Comparator.comparing(NoteView::pageKey));
        return out;
    }

    @Transactional(readOnly = true)
    public NoteView get(UUID practiceId, String pageKey, String lang) {
        List<HelpNote> versions = notes.findVisible(practiceId).stream().filter(n -> n.getPageKey().equals(pageKey)).toList();
        return pick(versions, language(lang)).orElseThrow(() -> new NotFoundException("No help for that page"));
    }

    /** Saves the clinic's own version of a note, leaving the built-in one untouched. */
    @Transactional
    public NoteView save(UUID practiceId, UUID actorId, String pageKey, String lang, String title, String body) {
        checkKey(pageKey);
        String language = requireLanguage(lang);
        HelpNote note = notes.findByPracticeIdAndPageKeyAndLang(practiceId, pageKey, language)
                .orElseGet(() -> HelpNote.builder().practiceId(practiceId).pageKey(pageKey).lang(language).build());
        note.setTitle(title.trim());
        note.setBody(body.trim());
        note.setUpdatedBy(actorId);
        return view(notes.save(note));
    }

    /** Drops the clinic's version so the built-in text shows again. */
    @Transactional
    public void reset(UUID practiceId, String pageKey, String lang) {
        notes.findByPracticeIdAndPageKeyAndLang(practiceId, pageKey, requireLanguage(lang)).ifPresent(notes::delete);
    }

    /** The notes most relevant to a question, best first, in the language asked for. */
    @Transactional(readOnly = true)
    public List<NoteView> relevant(UUID practiceId, String lang, String question, String currentPage, int limit) {
        Set<String> terms = HelpSearch.terms(question);
        List<NoteView> candidates = all(practiceId, lang);
        List<Map.Entry<NoteView, Integer>> scored = new ArrayList<>();
        for (NoteView n : candidates) {
            int score = HelpSearch.score(terms, n.title(), n.body()) + (n.pageKey().equals(currentPage) ? 1 : 0);
            if (HelpSearch.score(terms, n.title(), n.body()) > 0) {
                scored.add(Map.entry(n, score));
            }
        }
        scored.sort(Map.Entry.<NoteView, Integer>comparingByValue().reversed().thenComparing(e -> e.getKey().pageKey()));
        List<NoteView> out = scored.stream().limit(limit).map(Map.Entry::getKey).collect(Collectors.toCollection(ArrayList::new));
        if (out.isEmpty() && currentPage != null) {
            candidates.stream().filter(n -> n.pageKey().equals(currentPage)).findFirst().ifPresent(out::add);
        }
        return out;
    }

    static String language(String lang) {
        return lang != null && LANGUAGES.contains(lang.toLowerCase(Locale.ROOT)) ? lang.toLowerCase(Locale.ROOT) : "fr";
    }

    private static String requireLanguage(String lang) {
        if (lang == null || !LANGUAGES.contains(lang.toLowerCase(Locale.ROOT))) {
            throw new ValidationException("Language must be fr, en or ar");
        }
        return lang.toLowerCase(Locale.ROOT);
    }

    private static void checkKey(String pageKey) {
        if (pageKey == null || !PAGE_KEY.matcher(pageKey).matches()) {
            throw new ValidationException("A page key is lowercase letters, digits and hyphens");
        }
    }

    /** The clinic's version beats the built-in one; the asked-for language beats the fallbacks. */
    private static Optional<NoteView> pick(List<HelpNote> versions, String lang) {
        List<String> order = new ArrayList<>(List.of(lang));
        LANGUAGES.stream().filter(l -> !l.equals(lang)).forEach(order::add);
        for (String l : order) {
            Optional<HelpNote> best = versions.stream().filter(n -> n.getLang().equals(l))
                    .max(Comparator.comparing((HelpNote n) -> !n.isBuiltIn()));
            if (best.isPresent()) {
                return best.map(HelpService::view);
            }
        }
        return Optional.empty();
    }

    private static NoteView view(HelpNote n) {
        return new NoteView(n.getPageKey(), n.getLang(), n.getTitle(), n.getBody(), !n.isBuiltIn());
    }
}
