package com.orthoflow.insurance.infrastructure.forms;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

/**
 * The insurer's blank form with the patient's values written where its layout says.
 * The blanks carry no text of their own (one is an outlined drawing, the other a scan),
 * so anything a text extractor finds on a filled page is what OrthoFlow wrote.
 *
 * <p>Run with {@code -Dinsurance.forms.preview=/some/dir} to also keep the filled PDFs
 * there, to check by eye that each value sits in its box.
 */
class PdfFormFillerTest {

    private final FormLayouts layouts = new FormLayouts();
    private final PdfFormFiller filler = new PdfFormFiller();

    @Test
    void theShippedLayoutsAreFoundAndEachHasItsBlankForm() {
        assertThat(layouts.all()).extracting(FormLayout::code).contains("cnops-dentaire", "cnss-dentaire", "lamas-dentaire", "rma-dentaire");
        for (FormLayout l : layouts.all()) {
            assertThat(layouts.template(l)).as(l.code()).isNotEmpty();
        }
    }

    @Test
    void anInsurerGetsTheFormThatListsItsCodeUnlessTheClinicChoseAnother() {
        assertThat(layouts.forInsurer("MGPAP", null)).map(FormLayout::code).contains("cnops-dentaire");
        assertThat(layouts.forInsurer("cnss", null)).map(FormLayout::code).contains("cnss-dentaire");
        assertThat(layouts.forInsurer("CNSS", "cnops-dentaire")).map(FormLayout::code).contains("cnops-dentaire");
        assertThat(layouts.forInsurer("CNSS", FormLayouts.GENERIC)).isEmpty();
        assertThat(layouts.forInsurer("LAMAS", null)).map(FormLayout::code).contains("lamas-dentaire");
        assertThat(layouts.forInsurer("rma", null)).map(FormLayout::code).contains("rma-dentaire");
        assertThat(layouts.forInsurer("AXA", null)).isEmpty();
        assertThat(layouts.forInsurer(null, null)).isEmpty();
    }

    @Test
    void everyPlaceALayoutNamesLiesOnItsPage() throws IOException {
        for (FormLayout l : layouts.all()) {
            try (PDDocument doc = PDDocument.load(layouts.template(l))) {
                l.text().forEach((k, f) -> assertOnPage(doc, l, k, f.page(), f.x() + (f.w() == null ? 0 : f.w()), f.y()));
                l.cells().forEach((k, f) -> f.x().forEach(x -> assertOnPage(doc, l, k, f.page(), x, f.y())));
                l.checks().forEach((k, c) -> assertOnPage(doc, l, k, c.page(), c.x(), c.y()));
                for (float y : l.acts().rows()) {
                    l.acts().columns().forEach((k, c) -> assertOnPage(doc, l, k, l.acts().page(), c.x() + c.w(), y));
                }
            }
        }
    }

    @Test
    void theValuesAreWrittenOnTheInsurersOwnFormOnTheRightPages() throws IOException {
        for (String code : List.of("cnops-dentaire", "cnss-dentaire")) {
            FormLayout layout = layouts.find(code).orElseThrow();
            byte[] pdf = filler.fill(layout, layouts.template(layout), sample(), false);
            preview(code + ".pdf", pdf);
            try (PDDocument doc = PDDocument.load(pdf)) {
                assertThat(doc.getNumberOfPages()).isEqualTo(2);
                String front = text(doc, 1);
                assertThat(front).as(code).contains("BENNANI Karim").contains("BENNANI Yasmine").contains("1 250,00");
                String back = text(doc, 2);
                assertThat(back).as(code).contains("D629").contains("D 90").contains("11 21");
                // The form keeps its own print: the page still draws the blank's image.
                assertThat(doc.getPage(0).getResources().getXObjectNames()).isNotEmpty();
            }
        }
    }

    @Test
    void theLamasAndRmaFormsCarryTheirOwnWordsOnTheirOwnPages() throws IOException {
        FormLayout lamas = layouts.find("lamas-dentaire").orElseThrow();
        byte[] lamasPdf = filler.fill(lamas, layouts.template(lamas), sample(), false);
        preview("lamas-dentaire.pdf", lamasPdf);
        try (PDDocument doc = PDDocument.load(lamasPdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            assertThat(text(doc, 1)).contains("BENNANI Karim").contains("BENNANI Yasmine").contains("Enfant")
                    .contains("14/03/2014").contains("09/10/2026").contains("D 90").contains("1 000,00").contains("11 21");
            assertThat(text(doc, 2)).contains("Dr Amrani").contains("Cabinet Amrani").contains("1 250,00")
                    .contains("09/10/2026");
        }
        FormLayout rma = layouts.find("rma-dentaire").orElseThrow();
        byte[] rmaPdf = filler.fill(rma, layouts.template(rma), sample(), false);
        preview("rma-dentaire.pdf", rmaPdf);
        try (PDDocument doc = PDDocument.load(rmaPdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            assertThat(text(doc, 1)).contains("BENNANI Karim").contains("BENNANI Yasmine").contains("Enfant").contains("1 250,00");
            assertThat(text(doc, 2)).contains("09/10/26").contains("11 21").contains("D 90").contains("250,00");
        }
    }

    @Test
    void aKeyWithAHashIsTheSameValueWrittenAgain() {
        assertThat(PdfFormFiller.valueName("proposal.date#agreement")).isEqualTo("proposal.date");
        assertThat(PdfFormFiller.valueName("proposal.date")).isEqualTo("proposal.date");
    }

    @Test
    void theOverlayIsTheSameTextOnBlankPagesOfTheSameShape() throws IOException {
        FormLayout layout = layouts.find("cnss-dentaire").orElseThrow();
        byte[] blank = layouts.template(layout);
        byte[] pdf = filler.fill(layout, blank, sample(), true);
        preview("cnss-dentaire-overlay.pdf", pdf);
        try (PDDocument overlay = PDDocument.load(pdf); PDDocument form = PDDocument.load(blank)) {
            for (int i = 0; i < form.getNumberOfPages(); i++) {
                assertThat(overlay.getPage(i).getRotation()).isEqualTo(form.getPage(i).getRotation());
                assertThat(overlay.getPage(i).getMediaBox().getWidth()).isEqualTo(form.getPage(i).getMediaBox().getWidth());
                assertThat(overlay.getPage(i).getResources().getXObjectNames()).isEmpty();
            }
            assertThat(text(overlay, 1)).contains("BENNANI Karim");
        }
    }

    @Test
    void aValueTooLongForItsBoxIsShrunkThenCutVisibly() throws IOException {
        FormLayout layout = layouts.find("cnops-dentaire").orElseThrow();
        Map<String, String> text = new LinkedHashMap<>(sample().text());
        text.put("insured.fullName", "Abdelkrim Mohammed-Amine Benabdeljalil El Idrissi Ben Abdellah Al Alaoui Ech-Chorfi");
        byte[] pdf = filler.fill(layout, layouts.template(layout), new FormValues(text, Set.of(), List.of()), true);
        try (PDDocument doc = PDDocument.load(pdf)) {
            assertThat(text(doc, 1)).contains("Abdelkrim").contains("…").doesNotContain("Ech-Chorfi");
        }
    }

    @Test
    void theDisplayedPageIsMappedOntoThePagesOwnAxesForEveryRotation() {
        // A point 10 pt right of and 20 pt below the displayed top-left corner, on an A4 page.
        assertThat(origin(placementOn(0), 10, 20)).containsExactly(10f, 842f - 20f);
        assertThat(origin(placementOn(90), 10, 20)).containsExactly(20f, 10f);
        assertThat(origin(placementOn(180), 10, 20)).containsExactly(595f - 10f, 20f);
        assertThat(origin(placementOn(270), 10, 20)).containsExactly(595f - 20f, 842f - 10f);
    }

    @Test
    void arabicIsJoinedAndPutInReadingOrderWhileLatinIsLeftAlone() {
        assertThat(PdfFormFiller.visual("BENNANI Karim")).isEqualTo("BENNANI Karim");
        String shaped = PdfFormFiller.visual("كريم");
        assertThat(shaped).isNotEqualTo("كريم").hasSize(4);
        assertThat(shaped.codePoints()).allMatch(cp -> cp >= 0xFE70 && cp <= 0xFEFF);
    }

    /** A child insured through a parent, back for an orthodontic semester on the 9th of October 2026. */
    static FormValues sample() {
        Map<String, String> text = new LinkedHashMap<>();
        text.put("insured.fullName", "BENNANI Karim");
        text.put("insured.affiliation", "1234567");
        text.put("insured.immatriculation", "123456789");
        text.put("insured.cin", "BK123456");
        text.put("insured.phone", "0661 23 45 67");
        text.put("insured.address", "12, rue des Orangers, Maârif, Casablanca");
        text.put("total", "1 250,00");
        text.put("beneficiary.fullName", "BENNANI Yasmine");
        text.put("beneficiary.dobDigits", "14032014");
        text.put("beneficiary.cin", "");
        text.put("practitioner.inpe", "123456789");
        text.put("doctor.place", "Casablanca");
        text.put("doctor.dateDigits", "09102026");
        text.put("doctor.date", "09/10/2026");
        text.put("beneficiary.dob", "14/03/2014");
        text.put("beneficiary.relation", "Enfant");
        text.put("care.startDate", "09/10/2026");
        text.put("care.endDate", "09/10/2026");
        text.put("proposal.owner", "BENNANI Yasmine");
        text.put("proposal.ownerAddress", "12, rue des Orangers, Maârif, Casablanca");
        text.put("proposal.practitioner", "Dr Amrani");
        text.put("proposal.clinic", "Cabinet Amrani");
        text.put("proposal.clinicAddress", "5, bd Zerktouni, Casablanca");
        text.put("proposal.total", "1 250,00");
        text.put("proposal.date", "09/10/2026");
        List<Map<String, String>> rows = List.of(
                Map.of("teeth", "", "code", "C", "label", "Consultation", "date", "09/10/26", "cotation", "C 1",
                        "coefficient", "C 1", "amount", "250,00"),
                Map.of("teeth", "11 21", "code", "D629", "label", "Semestre ODF", "date", "09/10/26", "cotation", "D 90",
                        "coefficient", "D 90", "amount", "1 000,00"));
        return new FormValues(text, Set.of("purpose.EXECUTION", "relation.CHILD", "sex.F", "care.ODF"), rows);
    }

    private static void assertOnPage(PDDocument doc, FormLayout l, String key, int page, float x, float y) {
        assertThat(page).as(l.code() + " " + key + " page").isBetween(0, doc.getNumberOfPages() - 1);
        PDPage p = doc.getPage(page);
        boolean turned = p.getRotation() % 180 != 0;
        PDRectangle box = p.getCropBox();
        float width = turned ? box.getHeight() : box.getWidth();
        float height = turned ? box.getWidth() : box.getHeight();
        assertThat(x).as(l.code() + " " + key + " x").isBetween(0f, width);
        assertThat(y).as(l.code() + " " + key + " y").isBetween(0f, height);
    }

    private static Matrix placementOn(int rotation) {
        PDPage page = new PDPage(new PDRectangle(595, 842));
        page.setRotation(rotation);
        return PdfFormFiller.placement(page, 10, 20);
    }

    private static float[] origin(Matrix m, float dx, float dy) {
        return new float[] {m.getTranslateX(), m.getTranslateY()};
    }

    private static String text(PDDocument doc, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc).replaceAll("\\s+", " ");
    }

    private static void preview(String name, byte[] pdf) throws IOException {
        String dir = System.getProperty("insurance.forms.preview");
        if (dir != null) {
            Files.createDirectories(Path.of(dir));
            Files.write(Path.of(dir, name), pdf);
        }
    }
}
