package com.orthoflow.imaging.application.dto;

import com.orthoflow.imaging.domain.model.PhotoStage;
import com.orthoflow.imaging.domain.model.PhotoViewType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ClinicalPhotoDtos {

    private ClinicalPhotoDtos() {
    }

    @Schema(name = "ClinicalPhotoSeriesRequest")
    public record SeriesRequest(@NotNull PhotoStage stage, @NotNull LocalDate takenOn, @Size(max = 2000) String note) {
    }

    /** A view that has a picture. The image itself is {@code GET /files/{fileId}}. */
    @Schema(name = "ClinicalPhoto")
    public record Photo(PhotoViewType view, UUID fileId, String name, String contentType, long sizeBytes,
                        OffsetDateTime uploadedAt) {
    }

    /** A series with the views that have a picture; a view with none is simply absent. */
    @Schema(name = "ClinicalPhotoSeries")
    public record Series(UUID id, UUID patientId, PhotoStage stage, LocalDate takenOn, String note,
                         OffsetDateTime createdAt, List<Photo> photos) {
    }
}
