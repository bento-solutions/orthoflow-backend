package com.orthoflow.clinical.application.service;

import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportChart;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportChartTooth;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The printed chart: it draws what it was told, on the right tooth and the right side of it. */
class OdontogramRendererTest {

    /** Tooth 16's outline in the adult drawing: its first path command onward, enough to find the tooth. */
    private static Rectangle2D boundsOfTooth16() {
        return OdontogramRenderer.path(
                "m 40.400929,101.93638 c 8.540214,-6.220469 14.83636,-2.627509 21.132851,0.9639 1.70039,1.7707 3.363687,3.5413 5.692529,5.31326 "
                        + "7.131417,5.75158 5.79007,9.65482 1.660196,12.94987").getBounds2D();
    }

    /** Whether any pixel inside a box given in the chart's own units passes the test. */
    private static boolean anyPixel(BufferedImage image, Rectangle2D frame, Rectangle2D box, java.util.function.Predicate<Color> test) {
        double scale = image.getWidth() / frame.getWidth();
        for (int y = (int) ((box.getMinY() - frame.getY()) * scale); y < (box.getMaxY() - frame.getY()) * scale; y++) {
            for (int x = (int) ((box.getMinX() - frame.getX()) * scale); x < (box.getMaxX() - frame.getX()) * scale; x++) {
                if (test.test(new Color(image.getRGB(x, y)))) return true;
            }
        }
        return false;
    }

    private static boolean reddish(Color c) {
        return c.getRed() > 160 && c.getGreen() < 110 && c.getBlue() < 110;
    }

    @Test
    void aPathWithRelativeCurvesAndALineLandsWhereItSays() {
        Path2D path = OdontogramRenderer.path("m 10,10 l 5,0 c 0,5 0,5 -5,5 z M 100,100 L 110,110");
        Rectangle2D box = path.getBounds2D();

        assertThat(box.getMinX()).isEqualTo(10);
        assertThat(box.getMinY()).isEqualTo(10);
        assertThat(box.getMaxX()).isEqualTo(110);
        assertThat(box.getMaxY()).isEqualTo(110);
    }

    @Test
    void aTeethWithCariesIsRedOnThatToothAndNotElsewhere() {
        BufferedImage clean = OdontogramRenderer.chartImage(new PassportChart("adult", List.of(), List.of()), Map.of());
        BufferedImage painted = OdontogramRenderer.chartImage(new PassportChart("adult",
                List.of(new PassportChartTooth("16", "caries", List.of("A"), false, false)), List.of()), Map.of());

        Rectangle2D frame = adultFrame();
        Rectangle2D tooth16 = boundsOfTooth16();
        assertThat(anyPixel(clean, frame, tooth16, OdontogramRendererTest::reddish)).isFalse();
        assertThat(anyPixel(painted, frame, tooth16, OdontogramRendererTest::reddish)).isTrue();
        // The other side of the mouth stays clean.
        Rectangle2D mirror = new Rectangle2D.Double(290 - tooth16.getMaxX(), tooth16.getMinY(), tooth16.getWidth(), tooth16.getHeight());
        assertThat(anyPixel(painted, frame, mirror, OdontogramRendererTest::reddish)).isFalse();
    }

    /** The frame the renderer crops the adult drawing to, found by what is not white around it. */
    private static Rectangle2D adultFrame() {
        return OdontogramRenderer.frameOf(OdontogramRenderer.loadForTest("adult"));
    }

    @Test
    void aChildsSmallerArchesFillThePageLikeAnAdults() {
        BufferedImage adult = OdontogramRenderer.chartImage(new PassportChart("adult", List.of(), List.of()), Map.of());
        BufferedImage child = OdontogramRenderer.chartImage(new PassportChart("child", List.of(), List.of()), Map.of());

        assertThat(child.getWidth()).isEqualTo(adult.getWidth());
        Rectangle2D childFrame = OdontogramRenderer.frameOf(OdontogramRenderer.loadForTest("child"));
        assertThat(childFrame.getWidth()).isLessThan(adultFrame().getWidth());
    }

    @Test
    void mesialIsTowardTheMidlineOnEitherSideOfTheMouth() {
        assertThat(OdontogramRenderer.zoneOf("16", "mesial")).isEqualTo("right");
        assertThat(OdontogramRenderer.zoneOf("16", "distal")).isEqualTo("left");
        assertThat(OdontogramRenderer.zoneOf("26", "mesial")).isEqualTo("left");
        assertThat(OdontogramRenderer.zoneOf("36", "mesial")).isEqualTo("left");
        assertThat(OdontogramRenderer.zoneOf("46", "mesial")).isEqualTo("right");
        assertThat(OdontogramRenderer.zoneOf("54", "mesial")).isEqualTo("right");
        assertThat(OdontogramRenderer.zoneOf("11", "incisal")).isEqualTo("center");
        assertThat(OdontogramRenderer.zoneOf("11", "cervical")).isNull();
    }

    @Test
    void aPartTakesTheStrongestThingRecordedOnIt() {
        Map<String, Map<String, String>> marks = new HashMap<>();
        OdontogramRenderer.addMark(marks, "16", List.of("mesial"), "TREATMENT_REQUIRED");
        OdontogramRenderer.addMark(marks, "16", List.of("mesial"), "EXISTING");
        OdontogramRenderer.addMark(marks, "16", List.of("mesial", "occlusal"), "CONDITION");
        OdontogramRenderer.addMark(marks, "16", List.of("occlusal"), "EXISTING");

        assertThat(marks.get("16")).containsEntry("right", "condition").containsEntry("center", "condition");
    }

    @Test
    void bothChartsHaveEveryToothNumbered() {
        // Every tooth has an outline and a place for its number, or loading the drawing fails here.
        assertThat(OdontogramRenderer.chartDataUri(new PassportChart("adult", List.of(), List.of()), Map.of())).startsWith("data:image/png;base64,");
        assertThat(OdontogramRenderer.chartDataUri(new PassportChart("child", List.of(), List.of()), Map.of())).startsWith("data:image/png;base64,");
    }

    @Test
    void lettersRunOutToDoubleLetters() {
        assertThat(TreatmentChartBuilder.letter(0)).isEqualTo("A");
        assertThat(TreatmentChartBuilder.letter(25)).isEqualTo("Z");
        assertThat(TreatmentChartBuilder.letter(26)).isEqualTo("AA");
        assertThat(TreatmentChartBuilder.letter(27)).isEqualTo("AB");
    }
}
