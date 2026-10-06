package com.orthoflow.help.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class HelpDtos {

    private HelpDtos() {
    }

    /** {@code customised} is true when the clinic has replaced the built-in text; {@code lang} is the language actually served. */
    public record NoteView(String pageKey, String lang, String title, String body, boolean customised) {
    }

    public record SaveNote(@NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 8000) String body) {
    }

    public record AskRequest(@NotBlank String question, String lang, String pageKey) {
    }

    public record Source(String pageKey, String title) {
    }

    /**
     * {@code aiUsed} is false when the assistant is switched off or unavailable: then
     * there is no {@code answer} and {@code sources} are simply the most relevant notes.
     */
    public record AskResponse(String answer, boolean aiUsed, List<Source> sources, String notice) {
    }
}
