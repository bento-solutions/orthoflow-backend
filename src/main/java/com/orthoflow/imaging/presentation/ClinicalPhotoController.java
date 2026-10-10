package com.orthoflow.imaging.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.imaging.application.dto.ClinicalPhotoDtos.Series;
import com.orthoflow.imaging.application.dto.ClinicalPhotoDtos.SeriesRequest;
import com.orthoflow.imaging.application.service.ClinicalPhotoService;
import com.orthoflow.imaging.domain.model.PhotoViewType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * A patient's orthodontic photo series. Reading needs the clinical-record read
 * permission and changing them the write one (SecurityConfig); the pictures
 * themselves are served by {@code /files/{id}} under the same permission.
 */
@RestController
@RequiredArgsConstructor
public class ClinicalPhotoController {

    private final ClinicalPhotoService service;
    private final CurrentUserProvider currentUser;

    @GetMapping("/patients/{patientId}/photo-series")
    public List<Series> list(@PathVariable UUID patientId) {
        return service.list(currentUser.requirePracticeId(), patientId);
    }

    @PostMapping("/patients/{patientId}/photo-series")
    @ResponseStatus(HttpStatus.CREATED)
    public Series create(@PathVariable UUID patientId, @Valid @RequestBody SeriesRequest request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), patientId, request);
    }

    @PutMapping("/photo-series/{seriesId}")
    public Series update(@PathVariable UUID seriesId, @Valid @RequestBody SeriesRequest request) {
        return service.update(currentUser.requirePracticeId(), seriesId, request);
    }

    @DeleteMapping("/photo-series/{seriesId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID seriesId) {
        service.delete(currentUser.requirePracticeId(), seriesId);
    }

    @PutMapping(value = "/photo-series/{seriesId}/photos/{view}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Series upload(@PathVariable UUID seriesId, @PathVariable PhotoViewType view, @RequestPart("file") MultipartFile file) {
        return service.upload(currentUser.requirePracticeId(), currentUser.requireUserId(), seriesId, view, file);
    }

    @DeleteMapping("/photo-series/{seriesId}/photos/{view}")
    public Series removePhoto(@PathVariable UUID seriesId, @PathVariable PhotoViewType view) {
        return service.removePhoto(currentUser.requirePracticeId(), seriesId, view);
    }
}
