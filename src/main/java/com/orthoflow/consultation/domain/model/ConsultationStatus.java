package com.orthoflow.consultation.domain.model;

/**
 * Where a full-consultation recording is. The order is the order a consultation
 * goes through, and each step is the doctor's call, never inferred.
 */
public enum ConsultationStatus {
    /**
     * The conversation: the microphone is open, everything said is transcribed,
     * and what matters in it is proposed on the side panel. Nothing is
     * dictated to the chart yet.
     */
    INTAKE,

    /**
     * The doctor has called the consultation. The conversation is still
     * transcribed, and now the wake-word commands for the dental chart work as
     * they do in a dictated examination.
     */
    EXAMINATION,

    /**
     * Recording has stopped. The doctor validates, corrects or removes every
     * item the system caught, sets the next appointment, and saves.
     */
    REVIEW,

    /** Saved to the record. Final. */
    COMPLETED,

    /** Thrown away; nothing from it reached the record. */
    ABANDONED;

    /** Still being worked on — not saved, not thrown away. */
    public boolean isOpen() {
        return this == INTAKE || this == EXAMINATION || this == REVIEW;
    }

    /** The transcript can still grow. */
    public boolean isRecording() {
        return this == INTAKE || this == EXAMINATION;
    }
}
