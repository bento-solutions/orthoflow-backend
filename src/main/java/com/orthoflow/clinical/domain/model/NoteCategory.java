package com.orthoflow.clinical.domain.model;

/**
 * Where a dictated note belongs. The voice layer resolves this from the
 * utterance where it can and asks the doctor where it cannot — guessing
 * between DENTAL_HISTORY and MEDICAL_HISTORY is exactly the kind of silent
 * assumption a clinical record must not make.
 */
public enum NoteCategory {
    GENERAL,
    CHIEF_COMPLAINT,
    OBSERVATION,
    DENTAL_HISTORY,
    MEDICAL_HISTORY,
    DIAGNOSIS,
    FOLLOW_UP,
    TREATMENT_PLAN,

    /**
     * The narrative of a whole dictated examination, as the dentist edited and
     * signed it at review. Written by the commit step, never dictated as a
     * command: one per examination, replaced rather than duplicated if the
     * examination is saved again.
     */
    CONSULTATION_REPORT,

    /**
     * Everything that was said in a full-consultation recording, as
     * transcribed — kept so the doctor can review it later. Written by the
     * consultation commit, never dictated as a command: one per consultation,
     * replaced rather than duplicated if the consultation is saved again.
     */
    CONSULTATION_TRANSCRIPT
}
