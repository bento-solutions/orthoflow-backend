package com.orthoflow.export.application.dto;

import com.orthoflow.common.exception.ValidationException;

import java.util.Locale;

/** The three shapes every report can leave in: {@code GET …?format=pdf|xlsx|csv}. */
public enum ExportFormat {
    PDF("application/pdf", "pdf"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    CSV("text/csv; charset=UTF-8", "csv");

    private final String mediaType;
    private final String extension;

    ExportFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    public static ExportFormat parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PDF;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown export format '" + raw + "'; use pdf, xlsx or csv");
        }
    }
}
