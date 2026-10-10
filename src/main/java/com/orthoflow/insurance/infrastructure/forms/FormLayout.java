package com.orthoflow.insurance.infrastructure.forms;

import java.util.List;
import java.util.Map;

/**
 * Where each value goes on one insurer's paper form, read from
 * {@code insurance-forms/<code>/layout.json} beside the blank form itself.
 *
 * <p>Coordinates are PDF points on the page <em>as it is displayed</em>: origin at the
 * top-left corner, y growing downwards, y naming the text baseline. That is how the
 * positions were measured (on the page rendered at 72 dpi times a scale), and it keeps
 * a rotated scan (the CNSS sheet is stored on its side) as easy to describe as an
 * upright one: {@link PdfFormFiller} turns them into the page's own coordinates.
 *
 * @param insurerCodes the insurers whose patients file this form unless the clinic says otherwise
 * @param official     false for a copy found on a third-party site rather than published by the insurer
 * @param singleUse    the insurer numbers each blank and refuses photocopies: print on the patient's own form
 */
public record FormLayout(String code, String name, List<String> insurerCodes, String template, String source,
                         boolean official, boolean singleUse, String note, float fontSize,
                         Map<String, TextField> text, Map<String, CellsField> cells, Map<String, Check> checks,
                         Acts acts) {

    /** A line of text from (x, y); shrunk, then cut, to fit {@code w} when given. */
    public record TextField(int page, float x, float y, Float w, Float size, String align) {
    }

    /** One character per printed box, centred on each x. */
    public record CellsField(int page, float y, List<Float> x, Float size) {
    }

    /** A box to tick, centred on (x, y). */
    public record Check(int page, float x, float y, Float size) {
    }

    /** The table of acts: one line per baseline in {@code rows}, one column per value of a line. */
    public record Acts(int page, List<Float> rows, Float size, Map<String, Column> columns) {
    }

    public record Column(float x, float w, String align) {
    }
}
