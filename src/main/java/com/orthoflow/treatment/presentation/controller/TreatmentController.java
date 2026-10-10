package com.orthoflow.treatment.presentation.controller;

import com.orthoflow.treatment.application.dto.TreatmentPriceResponse;
import com.orthoflow.treatment.application.dto.TreatmentRequest;
import com.orthoflow.treatment.application.dto.TreatmentResponse;
import com.orthoflow.treatment.application.service.TreatmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/stock/treatments")
@RequiredArgsConstructor
public class TreatmentController {

    private final TreatmentService treatmentService;

    @GetMapping
    public ResponseEntity<List<TreatmentResponse>> getAllTreatments() {
        return ResponseEntity.ok(treatmentService.getAllTreatments().stream()
                .map(TreatmentResponse::from).collect(Collectors.toList()));
    }

    /** The price on a part of the tooth: {@code ?surface=mesial-occlusal} (omit for the base price). */
    @GetMapping("/{id}/price")
    public ResponseEntity<TreatmentPriceResponse> priceFor(@PathVariable UUID id,
                                                           @RequestParam(required = false) String surface) {
        return ResponseEntity.ok(treatmentService.priceFor(id, surface));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TreatmentResponse> getTreatmentById(@PathVariable UUID id) {
        return treatmentService.getTreatmentById(id)
                .map(t -> ResponseEntity.ok(TreatmentResponse.from(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<TreatmentResponse> getTreatmentByCode(@PathVariable String code) {
        return treatmentService.getTreatmentByCode(code)
                .map(t -> ResponseEntity.ok(TreatmentResponse.from(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<TreatmentResponse> createTreatment(@Valid @RequestBody TreatmentRequest request) {
        return ResponseEntity.ok(TreatmentResponse.from(treatmentService.saveTreatment(request, null)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TreatmentResponse> updateTreatment(@PathVariable UUID id, @Valid @RequestBody TreatmentRequest request) {
        return ResponseEntity.ok(TreatmentResponse.from(treatmentService.saveTreatment(request, id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTreatment(@PathVariable UUID id) {
        treatmentService.deleteTreatment(id);
        return ResponseEntity.noContent().build();
    }
}
