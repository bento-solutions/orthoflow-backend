package com.orthoflow.consultation.application.dto;

/**
 * What the browser needs to know before it offers the Consultation button.
 *
 * @param enabled         the feature is on in this deployment
 * @param modelExtraction a model reads the conversation; false means only the
 *                        rule-based reading (phone, CIN, age, insurer, allergies)
 * @param retainTranscript the raw conversation is kept in the patient's file once
 *                        saved (testing only); decides what the doctor tells the patient
 */
public record ConsultationConfigResponse(boolean enabled, boolean modelExtraction, boolean retainTranscript) {}
