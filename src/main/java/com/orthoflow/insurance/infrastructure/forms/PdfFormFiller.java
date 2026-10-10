package com.orthoflow.insurance.infrastructure.forms;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes values onto an insurer's own blank form, at the places its {@link FormLayout}
 * names. The form keeps every line of its print; only text is added, so what comes
 * out is the insurer's sheet with the boxes filled, ready for the doctor's stamp.
 *
 * <p>The overlay variant draws the same text on blank pages of the same size and
 * orientation, for printing straight onto a numbered paper form the patient brings
 * (CMIM, Wafa and AXA number theirs and refuse photocopies).
 *
 * <p>PDFBox writes glyphs in logical order and does not join Arabic letters, so a value
 * with Arabic in it (a name typed in Arabic) is shaped and put in visual order first.
 */
@Component
public class PdfFormFiller {

    private static final String FONT = "net/sf/jasperreports/fonts/dejavu/DejaVuSans.ttf";
    private static final float MIN_SIZE = 5.5f;
    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF\\u0750-\\u077F\\uFB50-\\uFDFF\\uFE70-\\uFEFF]");

    public byte[] fill(FormLayout layout, byte[] template, FormValues values, boolean overlayOnly) {
        try (PDDocument source = PDDocument.load(template)) {
            if (!overlayOnly) {
                return draw(layout, values, source);
            }
            try (PDDocument blank = blankLike(source)) {
                return draw(layout, values, blank);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not fill the form " + layout.code(), e);
        }
    }

    private byte[] draw(FormLayout layout, FormValues values, PDDocument target) throws IOException {
        PDFont font = loadFont(target);
        int pages = target.getNumberOfPages();
        PDPageContentStream[] streams = new PDPageContentStream[pages];
        try {
            for (int i = 0; i < pages; i++) {
                streams[i] = new PDPageContentStream(target, target.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true);
                // A dark blue, like a pen: what was added reads apart from the printed form.
                streams[i].setNonStrokingColor(0.05f, 0.1f, 0.35f);
            }
            Pen pen = new Pen(target, streams, font);
            drawText(layout, values, pen);
            drawCells(layout, values, pen);
            drawChecks(layout, values, pen);
            drawActs(layout, values, pen);
        } finally {
            for (PDPageContentStream s : streams) {
                if (s != null) s.close();
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            target.save(out);
            return out.toByteArray();
        }
    }

    private void drawText(FormLayout layout, FormValues values, Pen pen) throws IOException {
        if (layout.text() == null) return;
        for (Map.Entry<String, FormLayout.TextField> e : layout.text().entrySet()) {
            String value = values.text(valueName(e.getKey()));
            if (value == null) continue;
            FormLayout.TextField f = e.getValue();
            float size = f.size() != null ? f.size() : layout.fontSize();
            pen.write(f.page(), f.x(), f.y(), f.w(), size, f.align(), value);
        }
    }

    private void drawCells(FormLayout layout, FormValues values, Pen pen) throws IOException {
        if (layout.cells() == null) return;
        for (Map.Entry<String, FormLayout.CellsField> e : layout.cells().entrySet()) {
            String value = values.text(valueName(e.getKey()));
            if (value == null) continue;
            FormLayout.CellsField f = e.getValue();
            String chars = value.replaceAll("\\s+", "");
            float size = f.size() != null ? f.size() : layout.fontSize();
            for (int i = 0; i < Math.min(chars.length(), f.x().size()); i++) {
                pen.write(f.page(), f.x().get(i), f.y(), null, size, "center", String.valueOf(chars.charAt(i)));
            }
        }
    }

    private void drawChecks(FormLayout layout, FormValues values, Pen pen) throws IOException {
        if (layout.checks() == null) return;
        for (Map.Entry<String, FormLayout.Check> e : layout.checks().entrySet()) {
            if (!values.checked(valueName(e.getKey()))) continue;
            FormLayout.Check c = e.getValue();
            float size = c.size() != null ? c.size() : 10f;
            // The baseline sits a little under the centre so the cross lands in the middle of the box.
            pen.write(c.page(), c.x(), c.y() + size * 0.36f, null, size, "center", "X");
        }
    }

    private void drawActs(FormLayout layout, FormValues values, Pen pen) throws IOException {
        FormLayout.Acts acts = layout.acts();
        if (acts == null) return;
        float size = acts.size() != null ? acts.size() : layout.fontSize();
        List<Map<String, String>> rows = values.rows();
        for (int i = 0; i < Math.min(rows.size(), acts.rows().size()); i++) {
            float y = acts.rows().get(i);
            for (Map.Entry<String, FormLayout.Column> c : acts.columns().entrySet()) {
                String value = rows.get(i).get(c.getKey());
                if (value == null || value.isBlank()) continue;
                FormLayout.Column col = c.getValue();
                pen.write(acts.page(), col.x(), y, col.w(), size, col.align(), value);
            }
        }
    }

    /**
     * The value a layout key asks for: "proposal.date#agreement" is "proposal.date" written a second time,
     * as a layout's keys must be unique but a form may repeat a value (the date of a proposal, twice).
     */
    static String valueName(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    /** Blank pages with the template's size and rotation, so overlay text lands where the form's boxes are. */
    private static PDDocument blankLike(PDDocument source) {
        PDDocument blank = new PDDocument();
        for (PDPage page : source.getPages()) {
            PDPage copy = new PDPage(page.getMediaBox());
            copy.setCropBox(page.getCropBox());
            copy.setRotation(page.getRotation());
            blank.addPage(copy);
        }
        return blank;
    }

    private static PDFont loadFont(PDDocument doc) throws IOException {
        try (InputStream in = PdfFormFiller.class.getClassLoader().getResourceAsStream(FONT)) {
            if (in == null) {
                throw new IllegalStateException("Font " + FONT + " is missing from the classpath");
            }
            return PDType0Font.load(doc, in, true);
        }
    }

    /** Text in display order: Arabic letters joined and the run reordered, anything else as typed. */
    static String visual(String text) {
        if (!ARABIC.matcher(text).find()) {
            return text;
        }
        try {
            String shaped = new ArabicShaping(ArabicShaping.LETTERS_SHAPE).shape(text);
            return new Bidi(shaped, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT).writeReordered(Bidi.DO_MIRRORING);
        } catch (ArabicShapingException e) {
            return text;
        }
    }

    /**
     * Writes on a page in its displayed orientation. A page's /Rotate turns the
     * displayed view relative to the content's own axes; the text matrix turns the
     * glyphs the same way, so they read upright on the printed sheet.
     */
    static final class Pen {
        private final PDDocument doc;
        private final PDPageContentStream[] streams;
        private final PDFont font;

        Pen(PDDocument doc, PDPageContentStream[] streams, PDFont font) {
            this.doc = doc;
            this.streams = streams;
            this.font = font;
        }

        void write(int pageIndex, float x, float y, Float width, float size, String align, String raw) throws IOException {
            if (pageIndex < 0 || pageIndex >= streams.length) return;
            String text = clean(visual(raw));
            float fitted = size;
            if (width != null && textWidth(text, size) > width) {
                fitted = Math.max(MIN_SIZE, size * width / textWidth(text, size));
                if (textWidth(text, fitted) > width) {
                    // Still too long at the smallest legible size: cut it, and show that it was cut.
                    while (text.length() > 1 && textWidth(text + "…", fitted) > width) {
                        text = text.substring(0, text.length() - 1);
                    }
                    text = text + "…";
                }
            }
            float w = textWidth(text, fitted);
            float dx = switch (align == null ? "left" : align) {
                case "right" -> x + (width == null ? 0 : width) - w;
                case "center" -> width == null ? x - w / 2 : x + (width - w) / 2;
                default -> x;
            };
            PDPageContentStream cs = streams[pageIndex];
            cs.beginText();
            cs.setFont(font, fitted);
            cs.setTextMatrix(placement(doc.getPage(pageIndex), dx, y));
            cs.showText(text);
            cs.endText();
        }

        private float textWidth(String text, float size) throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }

        /** Drops what the font cannot draw (control characters, a stray emoji) instead of failing the form. */
        private String clean(String text) {
            StringBuilder sb = new StringBuilder(text.length());
            text.codePoints().forEach(cp -> {
                if (Character.isISOControl(cp)) {
                    sb.append(' ');
                    return;
                }
                String s = new String(Character.toChars(cp));
                try {
                    font.encode(s);
                    sb.append(s);
                } catch (IOException | IllegalArgumentException e) {
                    sb.append('?');
                }
            });
            return sb.toString();
        }
    }

    /** The text matrix for a baseline point (dx, dy) on the displayed page, top-left origin. */
    static Matrix placement(PDPage page, float dx, float dy) {
        PDRectangle box = page.getCropBox();
        float llx = box.getLowerLeftX();
        float lly = box.getLowerLeftY();
        float w = box.getWidth();
        float h = box.getHeight();
        return switch (((page.getRotation() % 360) + 360) % 360) {
            case 90 -> new Matrix(0, 1, -1, 0, llx + dy, lly + dx);
            case 180 -> new Matrix(-1, 0, 0, -1, llx + w - dx, lly + dy);
            case 270 -> new Matrix(0, -1, 1, 0, llx + w - dy, lly + h - dx);
            default -> new Matrix(1, 0, 0, 1, llx + dx, lly + h - dy);
        };
    }
}
