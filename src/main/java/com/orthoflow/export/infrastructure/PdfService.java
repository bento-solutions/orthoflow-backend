package com.orthoflow.export.infrastructure;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.PageBox;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.dto.TableExport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders an XHTML template to a PDF. Templates live in {@code resources/pdf}
 * and are written once per document (invoice, fee note, report, statement, lab
 * order…); {@code table} is the generic one every tabular report shares.
 *
 * <p>Arabic needs two things the base renderer lacks: a font that has the
 * glyphs (DejaVu Sans is embedded from the classpath, since the runtime image
 * carries none) and bidi shaping, supplied by the rtl-support module. Both are
 * wired for every render, so French and Arabic documents come out of the same
 * path.
 */
@Component
public class PdfService {

    private static final String FONT_DIR = "net/sf/jasperreports/fonts/dejavu/";
    private static final String HAND_FONT_DIR = "pdf/fonts/";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    static final String FILL_ID = "page-fill";
    static final String END_ID = "page-end";
    private static final int MIN_FILL_PX = 8;
    private static final int FILL_SLACK_PX = 20;

    private final TemplateEngine templates;

    public PdfService(@Qualifier("pdfTemplateEngine") TemplateEngine templates) {
        this.templates = templates;
    }

    /** Renders {@code template} with {@code model}; {@code lang} (fr, en, ar) sets language and direction. */
    public byte[] render(String template, Map<String, Object> model, String lang) {
        String language = lang == null || lang.isBlank() ? "fr" : lang;
        Context context = new Context(Locale.forLanguageTag(language));
        context.setVariables(model);
        context.setVariable("lang", language);
        context.setVariable("dir", "ar".equals(language) ? "rtl" : "ltr");
        context.setVariable("generatedAt", STAMP.format(OffsetDateTime.now()));
        String xhtml = templates.process(template, context);
        boolean rtl = "ar".equals(language);
        if (!xhtml.contains("id=\"" + FILL_ID + "\"") || !xhtml.contains("id=\"" + END_ID + "\"")) {
            return toPdf(xhtml, rtl);
        }
        return toFilledPdf(template, context, xhtml, rtl);
    }

    /**
     * A short document should not leave its last quarter blank. Templates that close with totals, payment
     * terms or a signature mark two places: {@code #page-fill}, just before the closing block, and
     * {@code #page-end}, after it. The document is laid out once, the space left between the end and the
     * footer band of the last page is measured, and it is laid out again with the fill that tall, so the
     * closing block sits on the footer the way a printed form does, on one page or on the last of many.
     *
     * <p>The fill is only used when it lies on the same page as the end, and only kept when the second
     * layout has the same number of pages; otherwise the first layout is printed as it was.
     */
    private byte[] toFilledPdf(String template, Context context, String xhtml, boolean rtl) {
        ByteArrayOutputStream firstOut = new ByteArrayOutputStream();
        ByteArrayOutputStream secondOut = new ByteArrayOutputStream();
        PdfBoxRenderer first = renderer(xhtml, rtl, firstOut);
        PdfBoxRenderer second = null;
        try {
            first.layout();
            int fillPx = freeSpacePx(first);
            if (fillPx > MIN_FILL_PX) {
                context.setVariable("pageFill", fillPx);
                second = renderer(templates.process(template, context), rtl, secondOut);
                second.layout();
                if (pageCount(second) == pageCount(first)) {
                    second.createPDF();
                    return secondOut.toByteArray();
                }
            }
            first.createPDF();
            return firstOut.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not render the PDF: " + e.getMessage(), e);
        } finally {
            close(first);
            close(second);
        }
    }

    /** CSS pixels between the end marker and the bottom of its page's content area; 0 when the fill cannot help. */
    static int freeSpacePx(PdfBoxRenderer renderer) {
        Box root = renderer.getRootBox();
        Box fill = find(root, FILL_ID);
        Box end = find(root, END_ID);
        if (fill == null || end == null) {
            return 0;
        }
        List<PageBox> pages = root.getLayer().getPages();
        PageBox endPage = pageOf(pages, end.getAbsY());
        if (endPage == null || endPage != pageOf(pages, fill.getAbsY())) {
            return 0;
        }
        // The end marker is the last content, but the body's own bottom margin and padding follow it
        // and need their room too, or they spill onto an empty extra page.
        int contentBottom = Math.max(end.getAbsY(), root.getAbsY() + root.getHeight());
        int freeDots = endPage.getBottom() - contentBottom;
        float dotsPerPixel = renderer.getSharedContext().getDotsPerPixel();
        // Some slack: the closing block keeps a little air above the footer band, and rounding never pushes it
        // onto a new page.
        return Math.max(0, (int) Math.floor(freeDots / dotsPerPixel) - FILL_SLACK_PX);
    }

    private static PageBox pageOf(List<PageBox> pages, int y) {
        for (PageBox page : pages) {
            if (y >= page.getTop() && y < page.getBottom()) {
                return page;
            }
        }
        return null;
    }

    private static Box find(Box root, String id) {
        Deque<Box> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Box box = stack.pop();
            Element element = box.getElement();
            if (element != null && id.equals(element.getAttribute("id"))) {
                return box;
            }
            for (int i = box.getChildCount() - 1; i >= 0; i--) {
                stack.push(box.getChild(i));
            }
        }
        return null;
    }

    private static int pageCount(PdfBoxRenderer renderer) {
        return renderer.getRootBox().getLayer().getPages().size();
    }

    /** The generic report: clinic letterhead, a title, one table, optional totals row. */
    public byte[] renderTable(TableExport table, Letterhead letterhead, String lang) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", letterhead);
        model.put("title", table.title());
        model.put("subtitle", table.subtitle());
        model.put("columns", table.columns());
        model.put("rows", table.rows().stream().map(PdfService::texts).toList());
        model.put("totals", table.totals() == null ? null : texts(table.totals()));
        return render("table", model, lang);
    }

    private static List<String> texts(List<Object> row) {
        List<String> out = new ArrayList<>(row.size());
        for (Object cell : row) {
            out.add(Cells.text(cell));
        }
        return out;
    }

    private byte[] toPdf(String xhtml, boolean rtl) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfBoxRenderer renderer = renderer(xhtml, rtl, out);
        try {
            renderer.layout();
            renderer.createPDF();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not render the PDF: " + e.getMessage(), e);
        } finally {
            close(renderer);
        }
    }

    private static void close(PdfBoxRenderer renderer) {
        if (renderer != null) {
            try {
                renderer.close();
            } catch (RuntimeException ignored) {
                // Closing releases the document; it cannot change bytes already written.
            }
        }
    }

    private PdfBoxRenderer renderer(String xhtml, boolean rtl, ByteArrayOutputStream out) {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        // Tagged PDF/UA (audit A2): prescriptions, care forms, the passport and the chart are handed to
        // patients, and an untagged PDF is a picture of text to a screen reader. It needs every font
        // embedded (DejaVu, below), a <title> and a lang on each template, and alt text on images.
        builder.usePdfUaAccessbility(true);
        builder.withHtmlContent(xhtml, null);
        font(builder, "DejaVuSans.ttf", 400, BaseRendererBuilder.FontStyle.NORMAL);
        font(builder, "DejaVuSans-Bold.ttf", 700, BaseRendererBuilder.FontStyle.NORMAL);
        font(builder, "DejaVuSans-BoldOblique.ttf", 700, BaseRendererBuilder.FontStyle.ITALIC);
        // What the practitioner writes (treatments, a medicine and how to take it) is set in a handwriting
        // face; Aref Ruqaa carries the Arabic, which Indie Flower lacks. Both are SIL OFL, in pdf/fonts.
        handFont(builder, "IndieFlower-Regular.ttf", "Indie Flower");
        handFont(builder, "ArefRuqaa-Regular.ttf", "Aref Ruqaa");
        builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
        builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
        builder.defaultTextDirection(rtl
                ? BaseRendererBuilder.TextDirection.RTL
                : BaseRendererBuilder.TextDirection.LTR);
        builder.toStream(out);
        return builder.buildPdfRenderer();
    }

    private static void handFont(PdfRendererBuilder builder, String file, String family) {
        builder.useFont(() -> {
            InputStream stream = PdfService.class.getClassLoader().getResourceAsStream(HAND_FONT_DIR + file);
            if (stream == null) {
                throw new IllegalStateException("Font " + file + " is missing from the classpath");
            }
            return stream;
        }, family, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
    }

    /**
     * Also registered as the generic families: tagged PDF/UA embeds every font and will not fall back to
     * the built-in ones, and text that names no family (a page-number margin box does not inherit the
     * body's) would otherwise have none.
     */
    private static void font(PdfRendererBuilder builder, String file, int weight, BaseRendererBuilder.FontStyle style) {
        for (String family : List.of("DejaVu Sans", "serif", "sans-serif", "monospace")) {
            builder.useFont(() -> {
                InputStream stream = PdfService.class.getClassLoader().getResourceAsStream(FONT_DIR + file);
                if (stream == null) {
                    throw new IllegalStateException("Font " + file + " is missing from the classpath");
                }
                return stream;
            }, family, weight, style, true);
        }
    }
}
