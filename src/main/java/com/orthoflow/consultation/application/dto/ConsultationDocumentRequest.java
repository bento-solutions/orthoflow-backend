package com.orthoflow.consultation.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A consultation's paper, already worded by the app in the doctor's language: the report or the
 * assistant's sheet. The server lays it out in the shared frame (letterhead, legal footer, page
 * numbers), so it looks like every other document, and keeps the report in the patient's file.
 *
 * <p>Every value is printed as text, never as markup.
 */
public record ConsultationDocumentRequest(
        @NotNull UUID patientId,
        @NotNull Kind kind,
        @Pattern(regexp = "fr|en|ar") String lang,
        @NotNull @Size(max = 120) String title,
        @Size(max = 120) String dateText,
        @Size(max = 160) String doctorName,
        @Size(max = 40) List<@Valid Field> patient,
        @Size(max = 30) List<@Valid Section> sections,
        @Size(max = 120) String signature,
        @Size(max = 200) String confidential) {

    public enum Kind { REPORT, ASSISTANT_SHEET }

    public record Field(@Size(max = 80) String label, @Size(max = 400) String value) {
    }

    /** A titled part: running text, a list (allergies are listed in red), or a priced table. */
    public record Section(@Size(max = 120) String title,
                          @Size(max = 20000) String text,
                          @Size(max = 200) List<@Size(max = 2000) String> items,
                          boolean alert,
                          @Valid Table table) {
    }

    public record Table(@Size(max = 8) List<@Size(max = 80) String> headers,
                        @Size(max = 300) List<@Size(max = 8) List<@Size(max = 1000) String>> rows,
                        @Size(max = 120) String totalLabel,
                        @Size(max = 60) String total,
                        @Size(max = 400) String note) {
    }
}
