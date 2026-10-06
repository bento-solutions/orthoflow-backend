package com.orthoflow.sterilization;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.EndoDtos.FileView;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.EndoDtos.KitFileRequest;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.EndoDtos.KitView;
import com.orthoflow.sterilization.application.service.EndoService;
import com.orthoflow.sterilization.domain.model.EndoFile;
import com.orthoflow.sterilization.domain.model.EndoFileModel;
import com.orthoflow.sterilization.domain.model.SterilizationItem;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import com.orthoflow.sterilization.infrastructure.EndoFileJpaRepository;
import com.orthoflow.sterilization.infrastructure.EndoFileModelJpaRepository;
import com.orthoflow.sterilization.infrastructure.SterilizationItemJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A worn file breaks in a canal, so the kit stops being usable the moment any file in it is spent. */
class EndoServiceTest {

    private final UUID practice = UUID.randomUUID();
    private EndoFileModelJpaRepository models;
    private EndoFileJpaRepository files;
    private SterilizationItemJpaRepository items;
    private EndoService service;
    private SterilizationItem kit;

    @BeforeEach
    void setUp() {
        models = mock(EndoFileModelJpaRepository.class);
        files = mock(EndoFileJpaRepository.class);
        items = mock(SterilizationItemJpaRepository.class);
        service = new EndoService(models, files, items);
        kit = SterilizationItem.builder().id(UUID.randomUUID()).practiceId(practice).code("ENDO-1").name("Endo kit").kind(Kind.ENDO_KIT)
                .state(State.READY).active(true).build();
        when(items.findByIdAndPracticeId(kit.getId(), practice)).thenReturn(Optional.of(kit));
    }

    private EndoFileModel model(String name, int maxUses) {
        EndoFileModel m = EndoFileModel.builder().id(UUID.randomUUID()).practiceId(practice).name(name).maxUses(maxUses).active(true).build();
        when(models.findByIdAndPracticeId(m.getId(), practice)).thenReturn(Optional.of(m));
        return m;
    }

    private EndoFile file(EndoFileModel m, int uses) {
        return EndoFile.builder().id(UUID.randomUUID()).practiceId(practice).modelId(m.getId()).kitItemId(kit.getId()).useCount(uses)
                .createdAt(OffsetDateTime.now()).build();
    }

    private void liveFiles(List<EndoFileModel> ms, List<EndoFile> fs) {
        when(files.lockActiveFiles(kit.getId())).thenReturn(fs);
        when(models.findAllById(any())).thenReturn(ms);
        when(models.findByPracticeIdOrderByNameAsc(practice)).thenReturn(ms);
        when(files.findByKitItemIdInAndDiscardedAtIsNull(any())).thenReturn(fs);
    }

    @Test
    void usingAKitCountsOneUseAgainstEveryFile() {
        EndoFileModel x1 = model("X1", 5);
        EndoFileModel x2 = model("X2", 3);
        EndoFile a = file(x1, 0);
        EndoFile b = file(x2, 2);
        liveFiles(List.of(x1, x2), List.of(a, b));

        service.recordUse(practice, kit);

        assertThat(a.getUseCount()).isEqualTo(1);
        assertThat(b.getUseCount()).isEqualTo(3);
    }

    @Test
    void aKitWithASpentFileCannotBeUsedAndNoFileIsCounted() {
        EndoFileModel x1 = model("X1", 5);
        EndoFileModel single = model("Single use", 1);
        EndoFile fresh = file(x1, 1);
        EndoFile spent = file(single, 1);
        liveFiles(List.of(x1, single), List.of(fresh, spent));

        assertThatThrownBy(() -> service.recordUse(practice, kit)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("Single use").hasMessageContaining("discard and replace");
        assertThat(fresh.getUseCount()).as("the healthy file is not counted for a use that did not happen").isEqualTo(1);
        verify(files, never()).saveAll(any());
    }

    @Test
    void anEmptyKitIsUsableAndCountsNothing() {
        liveFiles(List.of(), List.of());
        service.recordUse(practice, kit);
        verify(files).saveAll(List.of());
    }

    @Test
    void theKitViewFlagsFilesAtAndNearTheirLimit() {
        EndoFileModel x1 = model("X1", 5);
        EndoFileModel single = model("Single use", 1);
        EndoFile fresh = file(x1, 1);
        EndoFile last = file(x1, 4);
        EndoFile spent = file(x1, 5);
        EndoFile unusedSingle = file(single, 0);
        liveFiles(List.of(x1, single), List.of(fresh, last, spent, unusedSingle));

        KitView view = service.kit(practice, kit.getId());

        assertThat(view.needsReplacement()).isTrue();
        FileView f0 = view.files().stream().filter(f -> f.id().equals(fresh.getId())).findFirst().orElseThrow();
        assertThat(f0.remaining()).isEqualTo(4);
        assertThat(f0.nearLimit()).isFalse();
        assertThat(view.files().stream().filter(f -> f.id().equals(last.getId())).findFirst().orElseThrow().nearLimit()).isTrue();
        assertThat(view.files().stream().filter(f -> f.id().equals(spent.getId())).findFirst().orElseThrow().atLimit()).isTrue();
        // A single-use file is always on its last use, which is not worth an alert of its own.
        assertThat(view.files().stream().filter(f -> f.id().equals(unusedSingle.getId())).findFirst().orElseThrow().nearLimit()).isFalse();
    }

    @Test
    void filesCanOnlyGoIntoAnEndoKit() {
        SterilizationItem tray = SterilizationItem.builder().id(UUID.randomUUID()).practiceId(practice).code("T-1").name("Tray").kind(Kind.TRAY).build();
        when(items.findByIdAndPracticeId(tray.getId(), practice)).thenReturn(Optional.of(tray));
        EndoFileModel m = model("X1", 5);

        assertThatThrownBy(() -> service.addFiles(practice, tray.getId(), new KitFileRequest(m.getId(), 1))).isInstanceOf(ValidationException.class);
    }

    @Test
    void aRetiredFileModelCannotBeAddedToAKit() {
        EndoFileModel m = model("Old", 3);
        m.setActive(false);

        assertThatThrownBy(() -> service.addFiles(practice, kit.getId(), new KitFileRequest(m.getId(), 1))).isInstanceOf(ValidationException.class);
    }
}
