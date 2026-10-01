package com.orthoflow.consultation.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * The whole conversation so far. The browser holds the complete text, so each
 * save replaces the last rather than appending to it — a lost request cannot
 * leave a gap in the middle.
 */
@Getter
@Setter
public class TranscriptRequest {

    /** About 100 000 words: far beyond any consultation, still a ceiling on a request body. */
    @NotNull
    @Size(max = 500_000)
    private String transcript = "";
}
