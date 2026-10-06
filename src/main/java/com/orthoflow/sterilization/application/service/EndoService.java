package com.orthoflow.sterilization.application.service;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.EndoDtos.*;
import com.orthoflow.sterilization.domain.model.EndoFile;
import com.orthoflow.sterilization.domain.model.EndoFileModel;
import com.orthoflow.sterilization.domain.model.SterilizationItem;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;
import com.orthoflow.sterilization.infrastructure.EndoFileJpaRepository;
import com.orthoflow.sterilization.infrastructure.EndoFileModelJpaRepository;
import com.orthoflow.sterilization.infrastructure.SterilizationItemJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Endodontic files wear out: each has a number of uses after which it risks
 * breaking in a canal. A kit is a sterilization item that holds files, each
 * counting its own uses. Using the kit counts one use against every file in it and
 * is refused outright when any file has reached its limit; that one is a patient
 * safety stop, not a warning.
 */
@Service
@RequiredArgsConstructor
public class EndoService {

    private final EndoFileModelJpaRepository models;
    private final EndoFileJpaRepository files;
    private final SterilizationItemJpaRepository items;

    // ── Models ──
    @Transactional(readOnly = true)
    public List<ModelView> listModels(UUID practiceId) {
        return models.findByPracticeIdOrderByNameAsc(practiceId).stream().map(EndoService::view).toList();
    }

    @Transactional
    public ModelView createModel(UUID practiceId, ModelRequest r) {
        if (models.existsByPracticeIdAndNameIgnoreCase(practiceId, r.name().trim())) {
            throw new ConflictException("A file model named " + r.name().trim() + " already exists");
        }
        return view(models.save(EndoFileModel.builder().practiceId(practiceId).name(r.name().trim()).brand(blank(r.brand()))
                .sizeTaper(blank(r.sizeTaper())).maxUses(r.maxUses()).active(r.active() == null || r.active()).build()));
    }

    @Transactional
    public ModelView updateModel(UUID practiceId, UUID id, ModelRequest r) {
        EndoFileModel m = requireModel(practiceId, id);
        if (!m.getName().equalsIgnoreCase(r.name().trim()) && models.existsByPracticeIdAndNameIgnoreCase(practiceId, r.name().trim())) {
            throw new ConflictException("A file model named " + r.name().trim() + " already exists");
        }
        m.setName(r.name().trim());
        m.setBrand(blank(r.brand()));
        m.setSizeTaper(blank(r.sizeTaper()));
        m.setMaxUses(r.maxUses());
        if (r.active() != null) {
            m.setActive(r.active());
        }
        return view(models.save(m));
    }

    // ── Kits ──
    @Transactional(readOnly = true)
    public List<KitView> kits(UUID practiceId) {
        List<SterilizationItem> kits = items.findByPracticeIdOrderByCodeAsc(practiceId).stream()
                .filter(i -> i.getKind() == Kind.ENDO_KIT && i.isActive()).toList();
        return assemble(practiceId, kits);
    }

    @Transactional(readOnly = true)
    public KitView kit(UUID practiceId, UUID kitItemId) {
        SterilizationItem kit = requireKit(practiceId, kitItemId);
        return assemble(practiceId, List.of(kit)).get(0);
    }

    @Transactional
    public KitView addFiles(UUID practiceId, UUID kitItemId, KitFileRequest r) {
        SterilizationItem kit = requireKit(practiceId, kitItemId);
        EndoFileModel model = requireModel(practiceId, r.modelId());
        if (!model.isActive()) {
            throw new ValidationException("The file model " + model.getName() + " is no longer in use");
        }
        for (int n = 0; n < r.quantity(); n++) {
            files.save(EndoFile.builder().practiceId(practiceId).modelId(model.getId()).kitItemId(kit.getId()).build());
        }
        return assemble(practiceId, List.of(kit)).get(0);
    }

    @Transactional
    public KitView discard(UUID practiceId, UUID fileId, String reason) {
        EndoFile file = files.findByIdAndPracticeId(fileId, practiceId).orElseThrow(() -> new NotFoundException("File not found"));
        if (!file.isDiscarded()) {
            file.setDiscardedAt(OffsetDateTime.now());
            file.setDiscardReason(blank(reason) == null ? "Discarded" : reason.trim());
            files.save(file);
        }
        return assemble(practiceId, List.of(requireKit(practiceId, file.getKitItemId()))).get(0);
    }

    /**
     * Counts one use against every live file of the kit, in the caller's
     * transaction, or refuses when any file is spent. Called when a kit is used on a patient.
     */
    @Transactional
    public void recordUse(UUID practiceId, SterilizationItem kit) {
        List<EndoFile> live = files.lockActiveFiles(kit.getId());
        Map<UUID, EndoFileModel> byId = models.findAllById(live.stream().map(EndoFile::getModelId).distinct().toList()).stream()
                .collect(Collectors.toMap(EndoFileModel::getId, Function.identity()));
        for (EndoFile f : live) {
            EndoFileModel m = byId.get(f.getModelId());
            if (f.getUseCount() >= m.getMaxUses()) {
                throw new ConflictException("A " + m.getName() + " file in kit " + kit.getCode() + " has reached its limit of "
                        + m.getMaxUses() + (m.getMaxUses() == 1 ? " use" : " uses") + "; discard and replace it before using the kit");
            }
        }
        live.forEach(f -> f.setUseCount(f.getUseCount() + 1));
        files.saveAll(live);
    }

    /** Files that are spent or on their last use, so the screen can say "replace before the next case". */
    @Transactional(readOnly = true)
    public List<EndoAlert> alerts(UUID practiceId) {
        List<EndoAlert> out = new ArrayList<>();
        for (KitView kit : kits(practiceId)) {
            for (FileView f : kit.files()) {
                if (f.atLimit() || f.nearLimit()) {
                    out.add(new EndoAlert(kit.item().id(), kit.item().code(), kit.item().name(), f.id(), f.modelName(), f.useCount(), f.maxUses()));
                }
            }
        }
        return out;
    }

    // ── Helpers ──
    private List<KitView> assemble(UUID practiceId, List<SterilizationItem> kits) {
        if (kits.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<EndoFile>> byKit = files.findByKitItemIdInAndDiscardedAtIsNull(kits.stream().map(SterilizationItem::getId).toList())
                .stream().sorted(Comparator.comparing(EndoFile::getCreatedAt)).collect(Collectors.groupingBy(EndoFile::getKitItemId));
        Map<UUID, EndoFileModel> modelById = models.findByPracticeIdOrderByNameAsc(practiceId).stream()
                .collect(Collectors.toMap(EndoFileModel::getId, Function.identity()));
        List<KitView> out = new ArrayList<>();
        for (SterilizationItem kit : kits) {
            List<FileView> views = byKit.getOrDefault(kit.getId(), List.of()).stream().map(f -> {
                EndoFileModel m = modelById.get(f.getModelId());
                int remaining = Math.max(0, m.getMaxUses() - f.getUseCount());
                boolean atLimit = remaining == 0;
                return new FileView(f.getId(), m.getId(), m.getName(), f.getUseCount(), m.getMaxUses(), remaining, atLimit,
                        !atLimit && m.getMaxUses() > 1 && remaining <= 1);
            }).toList();
            out.add(new KitView(ItemViews.of(kit), views, views.stream().anyMatch(FileView::atLimit)));
        }
        return out;
    }

    private SterilizationItem requireKit(UUID practiceId, UUID id) {
        SterilizationItem item = items.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Kit not found"));
        if (item.getKind() != Kind.ENDO_KIT) {
            throw new ValidationException(item.getCode() + " is not an endo kit");
        }
        return item;
    }

    private EndoFileModel requireModel(UUID practiceId, UUID id) {
        return models.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("File model not found"));
    }

    private static ModelView view(EndoFileModel m) {
        return new ModelView(m.getId(), m.getName(), m.getBrand(), m.getSizeTaper(), m.getMaxUses(), m.isActive());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
