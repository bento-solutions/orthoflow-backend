package com.orthoflow.sterilization;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.export.infrastructure.QrCodes;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.Action;
import com.orthoflow.sterilization.application.service.ItemViews;
import com.orthoflow.sterilization.domain.model.SterilizationCycle;
import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlResult;
import com.orthoflow.sterilization.domain.model.SterilizationItem;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SterilizationDomainTest {

    private final OffsetDateTime t0 = OffsetDateTime.parse("2026-10-06T08:00:00Z");

    private static SterilizationItem item(Kind kind, State state) {
        return SterilizationItem.builder().id(UUID.randomUUID()).code("K-1").name("Tray").kind(kind).state(state).active(true).build();
    }

    // ── The cycle ──
    @Test
    void anItemWalksTheWholeCycleInOrder() {
        SterilizationItem tray = item(Kind.TRAY, State.DIRTY);
        UUID cycle = UUID.randomUUID();

        tray.process(cycle, t0);
        assertThat(tray.getState()).isEqualTo(State.PROCESSED);
        assertThat(tray.getLastCycleId()).isEqualTo(cycle);

        tray.release(t0.plusHours(1));
        assertThat(tray.getState()).isEqualTo(State.READY);

        tray.use(t0.plusHours(2));
        assertThat(tray.getState()).isEqualTo(State.USED);
        assertThat(tray.getLastUsedAt()).isEqualTo(t0.plusHours(2));

        tray.sendToCleaning(t0.plusHours(3));
        assertThat(tray.getState()).isEqualTo(State.DIRTY);
        assertThat(tray.getStateChangedAt()).isEqualTo(t0.plusHours(3));
    }

    @Test
    void onlyASterileItemMayBeUsed() {
        for (State state : new State[]{State.USED, State.DIRTY, State.PROCESSED}) {
            SterilizationItem tray = item(Kind.TRAY, state);
            assertThatThrownBy(() -> tray.use(t0)).isInstanceOf(ConflictException.class).hasMessageContaining(state.name());
            assertThat(tray.getState()).isEqualTo(state);
        }
    }

    @Test
    void everyOtherMoveRefusesAnItemInTheWrongState() {
        assertThatThrownBy(() -> item(Kind.TRAY, State.READY).sendToCleaning(t0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> item(Kind.TRAY, State.DIRTY).sendToCleaning(t0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> item(Kind.TRAY, State.READY).process(UUID.randomUUID(), t0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> item(Kind.TRAY, State.USED).process(UUID.randomUUID(), t0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> item(Kind.TRAY, State.DIRTY).release(t0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> item(Kind.TRAY, State.READY).release(t0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aFailedControlRecallsReadyAndProcessedItemsButNotOnesAlreadyInUse() {
        for (State state : new State[]{State.READY, State.PROCESSED}) {
            SterilizationItem tray = item(Kind.TRAY, state);
            tray.recall(t0);
            assertThat(tray.getState()).isEqualTo(State.DIRTY);
        }
        assertThatThrownBy(() -> item(Kind.TRAY, State.USED).recall(t0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> item(Kind.TRAY, State.DIRTY).recall(t0)).isInstanceOf(ConflictException.class);
    }

    // ── Handpieces ──
    @Test
    void aHandpieceIsLubricatedAfterCleaningAndNotBefore() {
        SterilizationItem piece = item(Kind.HANDPIECE, State.DIRTY);
        piece.lubricate(t0);
        assertThat(piece.getLastLubricatedAt()).isEqualTo(t0);

        assertThatThrownBy(() -> item(Kind.HANDPIECE, State.READY).lubricate(t0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> item(Kind.HANDPIECE, State.USED).lubricate(t0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> item(Kind.TRAY, State.DIRTY).lubricate(t0)).isInstanceOf(ConflictException.class).hasMessageContaining("handpiece");
    }

    @Test
    void lubricationIsDueUntilItHasBeenDoneSinceTheLastUse() {
        SterilizationItem piece = item(Kind.HANDPIECE, State.READY);
        assertThat(piece.lubricationDue()).as("never lubricated").isTrue();

        piece.use(t0);
        piece.sendToCleaning(t0.plusMinutes(5));
        assertThat(piece.lubricationDue()).isTrue();

        piece.lubricate(t0.plusMinutes(10));
        assertThat(piece.lubricationDue()).as("lubricated after the last use").isFalse();

        piece.process(UUID.randomUUID(), t0.plusMinutes(20));
        piece.release(t0.plusMinutes(60));
        piece.use(t0.plusHours(5));
        assertThat(piece.lubricationDue()).as("used again since").isTrue();
    }

    @Test
    void onlyHandpiecesEverNeedLubrication() {
        assertThat(item(Kind.TRAY, State.DIRTY).lubricationDue()).isFalse();
        assertThat(item(Kind.ENDO_KIT, State.DIRTY).lubricationDue()).isFalse();
    }

    // ── Control ──
    @Test
    void aControlIsDecidedOnceAndAFailureIsFinal() {
        SterilizationCycle cycle = SterilizationCycle.builder().id(UUID.randomUUID()).startedAt(t0).build();
        UUID by = UUID.randomUUID();

        assertThat(cycle.decide(ControlResult.PASSED, by, "indicator green", t0.plusHours(1))).isTrue();
        assertThat(cycle.getControlResult()).isEqualTo(ControlResult.PASSED);
        assertThat(cycle.getFinishedAt()).as("a decided cycle has finished").isEqualTo(t0.plusHours(1));

        assertThat(cycle.decide(ControlResult.PASSED, by, null, t0.plusHours(2))).as("same result again changes nothing").isFalse();

        assertThat(cycle.decide(ControlResult.FAILED, by, "biological positive", t0.plusDays(2))).as("a late biological result").isTrue();
        assertThatThrownBy(() -> cycle.decide(ControlResult.PASSED, by, null, t0.plusDays(3))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> cycle.decide(ControlResult.PENDING, by, null, t0)).isInstanceOf(ConflictException.class);
    }

    // ── What a scan offers ──
    @Test
    void aScanOffersOnlyWhatIsAllowedInThatState() {
        assertThat(ItemViews.of(item(Kind.TRAY, State.READY)).nextActions()).containsExactly(Action.USE);
        assertThat(ItemViews.of(item(Kind.TRAY, State.USED)).nextActions()).containsExactly(Action.CLEAN);
        assertThat(ItemViews.of(item(Kind.TRAY, State.DIRTY)).nextActions()).containsExactly(Action.ADD_TO_CYCLE);
        assertThat(ItemViews.of(item(Kind.HANDPIECE, State.DIRTY)).nextActions()).containsExactly(Action.LUBRICATE, Action.ADD_TO_CYCLE);
        assertThat(ItemViews.of(item(Kind.TRAY, State.PROCESSED)).nextActions()).isEmpty();

        SterilizationItem retired = item(Kind.TRAY, State.READY);
        retired.setActive(false);
        assertThat(ItemViews.of(retired).nextActions()).isEmpty();
    }

    // ── Labels ──
    @Test
    void aLabelsQrCodeReadsBackAsExactlyWhatWasPrinted() throws Exception {
        String text = "OFS1:" + UUID.randomUUID().toString().replace("-", "");
        String uri = QrCodes.pngDataUri(text, 220);

        assertThat(uri).startsWith("data:image/png;base64,");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1))));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        LuminanceSource source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
        assertThat(new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText()).isEqualTo(text);
    }
}
