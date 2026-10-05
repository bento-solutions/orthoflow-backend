package com.orthoflow.export.application.port;

import com.orthoflow.export.application.dto.Letterhead;

import java.util.UUID;

/**
 * The clinic's letterhead, supplied by whichever module owns the practice
 * profile. A port so the export module does not depend on settings.
 */
public interface LetterheadProvider {

    Letterhead forPractice(UUID practiceId);
}
