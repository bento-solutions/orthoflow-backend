package com.orthoflow.imaging.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.imaging.application.dto.ClinicalPhotoDtos.Photo;
import com.orthoflow.imaging.application.dto.ClinicalPhotoDtos.Series;
import com.orthoflow.imaging.application.dto.ClinicalPhotoDtos.SeriesRequest;
import com.orthoflow.imaging.domain.model.ClinicalPhoto;
import com.orthoflow.imaging.domain.model.ClinicalPhotoSeries;
import com.orthoflow.imaging.domain.model.PhotoViewType;
import com.orthoflow.imaging.infrastructure.ClinicalPhotoJpaRepository;
import com.orthoflow.imaging.infrastructure.ClinicalPhotoSeriesJpaRepository;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A patient's orthodontic photo record: series of the standard views, one per
 * sitting, so the start of a treatment can be set beside its end.
 */
@Service
@RequiredArgsConstructor
public class ClinicalPhotoService {

    private final ClinicalPhotoSeriesJpaRepository seriesRepository;
    private final ClinicalPhotoJpaRepository photos;
    private final FileService files;
    private final PatientLookup patients;

    /** Every series of the patient, the most recent sitting first. */
    @Transactional(readOnly = true)
    public List<Series> list(UUID practiceId, UUID patientId) {
        requirePatient(patientId);
        List<ClinicalPhotoSeries> all = seriesRepository.findByPracticeIdAndPatientIdOrderByTakenOnDescCreatedAtDesc(practiceId, patientId);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ClinicalPhoto>> bySeries = photos.findBySeriesIdIn(all.stream().map(ClinicalPhotoSeries::getId).toList())
                .stream().collect(Collectors.groupingBy(ClinicalPhoto::getSeriesId));
        // One query for every file the patient's photos point at, rather than one per picture.
        Map<UUID, StoredFile> fileById = files.forOwner(practiceId, FileOwnerType.CLINICAL_PHOTO, patientId).stream()
                .collect(Collectors.toMap(StoredFile::getId, Function.identity()));
        return all.stream().map(s -> view(s, bySeries.getOrDefault(s.getId(), List.of()), fileById)).toList();
    }

    @Transactional
    public Series create(UUID practiceId, UUID actorId, UUID patientId, SeriesRequest request) {
        requirePatient(patientId);
        ClinicalPhotoSeries series = seriesRepository.save(ClinicalPhotoSeries.builder()
                .practiceId(practiceId)
                .patientId(patientId)
                .stage(request.stage())
                .takenOn(request.takenOn())
                .note(blankToNull(request.note()))
                .createdBy(actorId)
                .build());
        return view(series, List.of(), Map.of());
    }

    @Transactional
    public Series update(UUID practiceId, UUID seriesId, SeriesRequest request) {
        ClinicalPhotoSeries series = requireSeries(practiceId, seriesId);
        series.setStage(request.stage());
        series.setTakenOn(request.takenOn());
        series.setNote(blankToNull(request.note()));
        return single(practiceId, series);
    }

    /** Removes the series and the pictures in it, bytes included. */
    @Transactional
    public void delete(UUID practiceId, UUID seriesId) {
        ClinicalPhotoSeries series = requireSeries(practiceId, seriesId);
        List<ClinicalPhoto> inSeries = photos.findBySeriesId(seriesId);
        photos.deleteAll(inSeries);
        photos.flush();
        for (ClinicalPhoto photo : inSeries) {
            files.delete(practiceId, photo.getFileId());
        }
        seriesRepository.delete(series);
    }

    /**
     * Puts a picture in one view of a series, replacing what was there. Only images:
     * a PDF cannot be shown beside the other views, and the stored file would be
     * unreadable on the comparison screen.
     */
    @Transactional
    public Series upload(UUID practiceId, UUID actorId, UUID seriesId, PhotoViewType view, MultipartFile upload) {
        ClinicalPhotoSeries series = requireSeries(practiceId, seriesId);
        requireImage(upload);
        StoredFile stored = files.store(practiceId, FileOwnerType.CLINICAL_PHOTO, series.getPatientId(), upload, actorId);
        photos.findBySeriesIdAndView(seriesId, view).ifPresentOrElse(existing -> {
            UUID previous = existing.getFileId();
            existing.setFileId(stored.getId());
            existing.setUploadedBy(actorId);
            existing.setCreatedAt(stored.getCreatedAt());
            photos.saveAndFlush(existing);
            files.delete(practiceId, previous);
        }, () -> photos.save(ClinicalPhoto.builder()
                .practiceId(practiceId)
                .seriesId(seriesId)
                .view(view)
                .fileId(stored.getId())
                .uploadedBy(actorId)
                .build()));
        return single(practiceId, series);
    }

    @Transactional
    public Series removePhoto(UUID practiceId, UUID seriesId, PhotoViewType view) {
        ClinicalPhotoSeries series = requireSeries(practiceId, seriesId);
        ClinicalPhoto photo = photos.findBySeriesIdAndView(seriesId, view)
                .orElseThrow(() -> new NotFoundException("This view has no picture"));
        photos.delete(photo);
        photos.flush();
        files.delete(practiceId, photo.getFileId());
        return single(practiceId, series);
    }

    private Series single(UUID practiceId, ClinicalPhotoSeries series) {
        Map<UUID, StoredFile> fileById = files.forOwner(practiceId, FileOwnerType.CLINICAL_PHOTO, series.getPatientId()).stream()
                .collect(Collectors.toMap(StoredFile::getId, Function.identity()));
        return view(series, photos.findBySeriesId(series.getId()), fileById);
    }

    private static Series view(ClinicalPhotoSeries s, List<ClinicalPhoto> inSeries, Map<UUID, StoredFile> fileById) {
        List<Photo> views = inSeries.stream()
                .filter(p -> fileById.containsKey(p.getFileId()))
                .sorted(Comparator.comparing(ClinicalPhoto::getView))
                .map(p -> {
                    StoredFile f = fileById.get(p.getFileId());
                    return new Photo(p.getView(), f.getId(), f.getOriginalName(), f.getContentType(), f.getSizeBytes(), p.getCreatedAt());
                })
                .toList();
        return new Series(s.getId(), s.getPatientId(), s.getStage(), s.getTakenOn(), s.getNote(), s.getCreatedAt(), views);
    }

    private ClinicalPhotoSeries requireSeries(UUID practiceId, UUID seriesId) {
        return seriesRepository.findByIdAndPracticeId(seriesId, practiceId)
                .orElseThrow(() -> new NotFoundException("Photo series not found"));
    }

    private void requirePatient(UUID patientId) {
        if (!patients.exists(patientId)) {
            throw new NotFoundException("Patient not found");
        }
    }

    private static void requireImage(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new ValidationException("The file is empty");
        }
        byte[] bytes;
        try {
            bytes = upload.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!FileService.sniff(bytes).startsWith("image/")) {
            throw new ValidationException("Only PNG, JPEG and WebP pictures can be added to a photo series");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
