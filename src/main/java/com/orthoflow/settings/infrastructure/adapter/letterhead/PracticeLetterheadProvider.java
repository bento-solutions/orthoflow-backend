package com.orthoflow.settings.infrastructure.adapter.letterhead;

import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.settings.application.service.PracticeProfileService;
import com.orthoflow.settings.domain.model.PracticeProfile;
import com.orthoflow.storage.application.service.FileService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.UUID;

/** Supplies the exporter with the clinic's identity, logo inlined so the PDF needs no second fetch. */
@Component
@RequiredArgsConstructor
public class PracticeLetterheadProvider implements LetterheadProvider {

    private static final Logger log = LoggerFactory.getLogger(PracticeLetterheadProvider.class);

    private final PracticeProfileService profiles;
    private final FileService files;

    @Override
    public Letterhead forPractice(UUID practiceId) {
        PracticeProfile p = profiles.require(practiceId);
        return new Letterhead(p.getName(), p.getLegalName(), p.getIce(), p.getTaxId(), p.getPatente(), p.getRib(),
                p.getAddress(), p.getCity(), p.getPhone(), p.getEmail(), logo(p));
    }

    private String logo(PracticeProfile p) {
        if (p.getLogoFileId() == null) {
            return null;
        }
        try {
            var file = files.require(p.getId(), p.getLogoFileId());
            return "data:" + file.getContentType() + ";base64," + Base64.getEncoder().encodeToString(files.read(file));
        } catch (RuntimeException e) {
            // A missing logo must not stop an invoice being printed.
            log.warn("Could not load the logo for practice {}: {}", p.getId(), e.getMessage());
            return null;
        }
    }
}
