package com.orthoflow.consultation.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.consultation.application.dto.ConsultationDocumentRequest;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lays out a consultation's report or assistant's sheet in the shared document frame. The report is
 * part of the medical record, so a PDF copy is kept in the patient's documents each time it is made.
 */
@Service
@RequiredArgsConstructor
public class ConsultationDocumentService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final PdfService pdf;
    private final LetterheadProvider letterheads;
    private final PatientLookup patients;
    private final FileService files;

    public record Rendered(byte[] bytes, String fileName, UUID fileId) {
    }

    @Transactional
    public Rendered render(UUID practiceId, UUID actorId, ConsultationDocumentRequest request) {
        if (!patients.exists(request.patientId())) {
            throw new NotFoundException("Patient not found");
        }
        String lang = request.lang() == null ? "fr" : request.lang();
        Letterhead letterhead = letterheads.forPractice(practiceId);
        String doctor = doctorName(request.doctorName());

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", letterhead);
        model.put("doc", request);
        model.put("practitionerLine", doctor == null ? null : letterhead.practitionerLine(doctor, letterhead.inpe()));
        model.put("confidentialNote", blank(request.confidential()) ? null : request.confidential());
        model.put("sections", request.sections() == null ? List.of() : request.sections());
        model.put("patientRows", request.patient() == null ? List.of()
                : request.patient().stream().filter(f -> !blank(f.value())).toList());
        byte[] bytes = pdf.render("consultation-document", model, lang);

        String fileName = (request.kind() == ConsultationDocumentRequest.Kind.REPORT ? "compte-rendu-" : "fiche-assistante-")
                + DAY.format(LocalDate.now()) + ".pdf";
        UUID fileId = null;
        if (request.kind() == ConsultationDocumentRequest.Kind.REPORT) {
            fileId = files.storeGenerated(practiceId, FileOwnerType.PATIENT_DOCUMENT, request.patientId(), fileName,
                    "application/pdf", bytes, actorId).getId();
        }
        return new Rendered(bytes, fileName, fileId);
    }

    /** "Dr Sara Alaoui", whether the account's name already starts with "Dr" or not (it printed "Dr Dr"). */
    static String doctorName(String name) {
        if (blank(name)) {
            return null;
        }
        String bare = name.trim().replaceFirst("(?i)^(dr\\.?|docteur)\\s+", "");
        return bare.isBlank() ? null : "Dr " + bare;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
