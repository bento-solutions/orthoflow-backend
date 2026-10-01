package com.orthoflow.consultation.application.dto;

import com.orthoflow.consultation.domain.model.ConsultationDraft;

/**
 * What the conversation established so far.
 *
 * @param error     null when a model read the conversation; {@code extraction-disabled}
 *                  or {@code extraction-failed} when only the rule-based reading is
 *                  here (phone, CIN, age, insurer, allergies)
 * @param truncated the conversation was longer than the model is sent; the draft
 *                  describes its most recent part
 */
public record ExtractionResponse(ConsultationDraft draft, String error, boolean truncated) {}
