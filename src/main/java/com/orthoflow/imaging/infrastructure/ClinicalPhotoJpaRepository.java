package com.orthoflow.imaging.infrastructure;

import com.orthoflow.imaging.domain.model.ClinicalPhoto;
import com.orthoflow.imaging.domain.model.PhotoViewType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClinicalPhotoJpaRepository extends JpaRepository<ClinicalPhoto, UUID> {

    List<ClinicalPhoto> findBySeriesIdIn(Collection<UUID> seriesIds);

    List<ClinicalPhoto> findBySeriesId(UUID seriesId);

    Optional<ClinicalPhoto> findBySeriesIdAndView(UUID seriesId, PhotoViewType view);
}
