package com.orthoflow.consultation.application.dto;

import com.orthoflow.consultation.domain.model.ConsultationDraft;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationDraftParser.ChartMention;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationDraftParser.Correction;

import java.util.List;

/**
 * What the conversation established so far.
 *
 * @param error     null when a model read the conversation; {@code extraction-disabled}
 *                  or {@code extraction-failed} when only the rule-based reading is
 *                  here (phone, CIN, age, insurer, allergies)
 * @param truncated the conversation was longer than the model is sent; the draft
 *                  describes its most recent part
 * @param chartMentions findings on a tooth the reader heard; the browser stages or lists them
 * @param corrections   for a tail reading: what it corrects of what was already read
 * @param mode      {@code full} or {@code tail}; a tail answer is merged as a partial reading
 */
public record ExtractionResponse(ConsultationDraft draft, String error, boolean truncated,
                                 List<ChartMention> chartMentions, List<Correction> corrections, String mode) {
    public ExtractionResponse(ConsultationDraft draft, String error, boolean truncated) {
        this(draft, error, truncated, List.of(), List.of(), "full");
    }
}
