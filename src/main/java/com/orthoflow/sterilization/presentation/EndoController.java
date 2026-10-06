package com.orthoflow.sterilization.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.EndoDtos.*;
import com.orthoflow.sterilization.application.service.EndoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Endo file models and the kits that hold files. STERILIZATION_MANAGE (SecurityConfig). */
@RestController
@RequestMapping("/endo")
@RequiredArgsConstructor
public class EndoController {

    private final EndoService service;
    private final CurrentUserProvider currentUser;

    @GetMapping("/models")
    public List<ModelView> models() {
        return service.listModels(currentUser.requirePracticeId());
    }

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    public ModelView createModel(@Valid @RequestBody ModelRequest request) {
        return service.createModel(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/models/{id}")
    public ModelView updateModel(@PathVariable UUID id, @Valid @RequestBody ModelRequest request) {
        return service.updateModel(currentUser.requirePracticeId(), id, request);
    }

    @GetMapping("/kits")
    public List<KitView> kits() {
        return service.kits(currentUser.requirePracticeId());
    }

    @GetMapping("/kits/{kitItemId}")
    public KitView kit(@PathVariable UUID kitItemId) {
        return service.kit(currentUser.requirePracticeId(), kitItemId);
    }

    @PostMapping("/kits/{kitItemId}/files")
    @ResponseStatus(HttpStatus.CREATED)
    public KitView addFiles(@PathVariable UUID kitItemId, @Valid @RequestBody KitFileRequest request) {
        return service.addFiles(currentUser.requirePracticeId(), kitItemId, request);
    }

    @PostMapping("/files/{fileId}/discard")
    public KitView discard(@PathVariable UUID fileId, @RequestBody(required = false) DiscardRequest request) {
        return service.discard(currentUser.requirePracticeId(), fileId, request == null ? null : request.reason());
    }

    @GetMapping("/alerts")
    public List<EndoAlert> alerts() {
        return service.alerts(currentUser.requirePracticeId());
    }
}
