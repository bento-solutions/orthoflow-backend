package com.orthoflow.consultation.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole conversation so far. The browser holds the complete text, so each
 * save replaces the last rather than appending to it — a lost request cannot
 * leave a gap in the middle.
 *
 * <p>{@code lines} is the same conversation one utterance at a time, with the
 * time each was heard: the consultation log. Each is stored once, by its number,
 * so resending the full list on every reading costs nothing but bandwidth.
 */
@Getter
@Setter
public class TranscriptRequest {

    /** About 100 000 words: far beyond any consultation, still a ceiling on a request body. */
    @NotNull
    @Size(max = 500_000)
    private String transcript = "";

    /** Optional: an older browser sends only the text, and its consultation has no timed log. */
    @Valid
    @Size(max = 5_000)
    private List<Line> lines = new ArrayList<>();

    @Getter
    @Setter
    public static class Line {
        /** The browser's running number for the line; stable across resends. */
        @Min(0)
        private int seq;

        /** When it was heard, by the browser's clock; the server holds it to the consultation's span. */
        private OffsetDateTime at;

        @NotBlank
        @Size(max = 20_000)
        private String text;

        /** Typed by the doctor, not heard. */
        private boolean typed;

        /** INTAKE or EXAMINATION; the server falls back to the consultation's own phase. */
        @Size(max = 12)
        private String phase;
    }
}
