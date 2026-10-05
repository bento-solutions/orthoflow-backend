package com.orthoflow.settings.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.settings.application.dto.PracticeProfileDtos.*;
import com.orthoflow.settings.application.service.PracticeProfileService;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** The clinic's identity and weekly opening hours. Everyone reads; SETTINGS_MANAGE changes. */
@RestController
@RequestMapping("/settings/practice")
@RequiredArgsConstructor
public class PracticeProfileController {

    private final PracticeProfileService service;
    private final FileService fileService;
    private final CurrentUserProvider currentUser;

    @GetMapping("/profile")
    public Profile profile() {
        return service.get(currentUser.requirePracticeId());
    }

    @PutMapping("/profile")
    public Profile updateProfile(@Valid @RequestBody Profile request) {
        return service.update(currentUser.requirePracticeId(), request);
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Profile uploadLogo(@RequestPart("file") MultipartFile file) {
        UUID practiceId = currentUser.requirePracticeId();
        StoredFile stored = fileService.store(practiceId, FileOwnerType.PRACTICE_LOGO, practiceId, file, currentUser.requireUserId());
        service.setLogo(practiceId, stored.getId());
        return service.get(practiceId);
    }

    @GetMapping("/logo")
    public ResponseEntity<InputStreamResource> logo() {
        UUID practiceId = currentUser.requirePracticeId();
        UUID fileId = service.get(practiceId).logoFileId();
        if (fileId == null) {
            return ResponseEntity.notFound().build();
        }
        StoredFile file = fileService.require(practiceId, fileId);
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=3600")
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .body(new InputStreamResource(fileService.open(file)));
    }

    @GetMapping("/opening-hours")
    public Week openingHours() {
        return service.openingHours(currentUser.requirePracticeId());
    }

    @PutMapping("/opening-hours")
    public Week replaceOpeningHours(@Valid @RequestBody Week request) {
        return service.replaceOpeningHours(currentUser.requirePracticeId(), request);
    }
}
