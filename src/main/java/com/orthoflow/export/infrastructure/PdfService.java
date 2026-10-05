package com.orthoflow.export.infrastructure;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.dto.TableExport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

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
        return toPdf(xhtml, "ar".equals(language));
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
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(xhtml, null);
            font(builder, "DejaVuSans.ttf", 400, BaseRendererBuilder.FontStyle.NORMAL);
            font(builder, "DejaVuSans-Bold.ttf", 700, BaseRendererBuilder.FontStyle.NORMAL);
            font(builder, "DejaVuSans-BoldOblique.ttf", 700, BaseRendererBuilder.FontStyle.ITALIC);
            builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
            builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
            builder.defaultTextDirection(rtl
                    ? BaseRendererBuilder.TextDirection.RTL
                    : BaseRendererBuilder.TextDirection.LTR);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not render the PDF: " + e.getMessage(), e);
        }
    }

    private static void font(PdfRendererBuilder builder, String file, int weight, BaseRendererBuilder.FontStyle style) {
        builder.useFont(() -> {
            InputStream stream = PdfService.class.getClassLoader().getResourceAsStream(FONT_DIR + file);
            if (stream == null) {
                throw new IllegalStateException("Font " + file + " is missing from the classpath");
            }
            return stream;
        }, "DejaVu Sans", weight, style, true);
    }
}
