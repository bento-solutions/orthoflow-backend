package com.orthoflow.sterilization.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.export.infrastructure.QrCodes;
import com.orthoflow.sterilization.domain.model.SterilizationItem;
import com.orthoflow.sterilization.infrastructure.SterilizationItemJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * A sheet of QR labels to print and stick on trays. A label carries the item's
 * opaque token, its code and its name, and nothing about any patient: the code on
 * a tray that has been on a patient must tell a stranger nothing.
 */
@Service
@RequiredArgsConstructor
public class LabelService {

    private static final int MAX_LABELS = 120;

    private final SterilizationItemJpaRepository items;
    private final PdfService pdfService;
    private final LetterheadProvider letterheadProvider;

    @Transactional(readOnly = true)
    public byte[] sheet(UUID practiceId, List<UUID> itemIds, String lang) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new ValidationException("Choose at least one item to label");
        }
        Set<UUID> wanted = new LinkedHashSet<>(itemIds);
        if (wanted.size() > MAX_LABELS) {
            throw new ValidationException("At most " + MAX_LABELS + " labels fit in one print");
        }
        List<SterilizationItem> found = items.findAllById(wanted).stream().filter(i -> practiceId.equals(i.getPracticeId()))
                .sorted(Comparator.comparing(SterilizationItem::getCode)).toList();
        if (found.size() != wanted.size()) {
            throw new NotFoundException("One or more items were not found");
        }
        List<Map<String, Object>> labels = new ArrayList<>();
        for (SterilizationItem item : found) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("qr", QrCodes.pngDataUri(SterilizationService.QR_PREFIX + item.getQrToken(), 220));
            m.put("code", item.getCode());
            m.put("name", item.getName());
            m.put("kind", item.getKind().name());
            labels.add(m);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", letterheadProvider.forPractice(practiceId));
        model.put("labels", labels);
        return pdfService.render("sterilization-labels", model, "ar".equals(lang) || "en".equals(lang) ? lang : "fr");
    }
}
