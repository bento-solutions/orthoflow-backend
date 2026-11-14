package com.orthoflow.consultation.application.dto;

/**
 * What the browser needs to know before it offers the Consultation button.
 *
 * @param enabled         the feature is on in this deployment
 * @param modelExtraction a model reads the conversation; false means only the
 *                        rule-based reading (phone, CIN, age, insurer, allergies)
 * @param retainTranscript the timestamped conversation is kept in the patient's file once
 *                        saved, for the doctor to check later; decides what the doctor tells the patient
 * @param cockpit         the one-screen consultation cockpit while recording; false brings back
 *                        the two panels of before (the rollback, no rebuild needed)
 * @param tailReading     the browser may ask for fast readings of the conversation's end
 * @param defaultMode     {@code deferred} (nothing acts while recording; everything is turned into
 *                        commands and proposals when the consultation ends) or {@code live}
 * @param keepAudio       the audio of the conversation is recorded and kept with its transcript
 * @param maxAudioMegabytes the largest recording the server keeps
 */
public record ConsultationConfigResponse(boolean enabled, boolean modelExtraction, boolean retainTranscript,
                                         boolean cockpit, boolean tailReading, String defaultMode,
                                         boolean keepAudio, int maxAudioMegabytes) {}
