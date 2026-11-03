package com.orthoflow.clinical.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportChart;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportChartTooth;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws the dental chart for the printed documents: the same teeth, fills and surface
 * marks as the screen (frontend features/dental-chart), so what was checked on screen is
 * what is printed. Colours and pattern geometry mirror core/clinical/tooth-status.ts.
 *
 * <p>Every pattern is meant to survive a black-and-white photocopier, which is where a
 * care summary tends to end up: geometry says what the colour says.
 */
final class OdontogramRenderer {

    /** About 300 dpi at the 108 mm the documents print it. */
    private static final double PRINT_WIDTH_PX = 1280;
    /** Pixels per chart unit in a legend swatch. */
    private static final double SCALE = 5;

    private static final double CENTRE_X = 145;

    private static final Color EXISTING_GROUND = new Color(47, 143, 162, 41);
    private static final Color EXISTING_INK = new Color(33, 94, 111);
    private static final Color CONDITION_GROUND = new Color(230, 69, 69, 46);
    private static final Color CONDITION_INK = new Color(175, 32, 32);
    private static final Color ABSENT_GROUND = new Color(213, 221, 224, 179);
    private static final Color ABSENT_INK = new Color(99, 116, 122);
    private static final Color PLANNED_INK = new Color(173, 90, 6);
    private static final Color DONE_INK = new Color(18, 124, 74);
    private static final Color LINE_INK = new Color(51, 65, 85);
    private static final Color EXISTING_SOLID = new Color(47, 143, 162);
    private static final Color CONDITION_SOLID = new Color(230, 69, 69);
    private static final Color PLANNED_SOLID = new Color(207, 112, 8);
    private static final Color KEY_INK = new Color(16, 56, 67);

    private static final Map<String, Tooth[]> LAYOUTS = new ConcurrentHashMap<>();
    private static final Map<String, String> SWATCHES = new ConcurrentHashMap<>();

    record Tooth(String fdi, Path2D outline, List<Path2D> details, double labelX, double labelY) {}

    private OdontogramRenderer() {}

    /** The chart, as a data URI the PDF template can place. */
    static String chartDataUri(PassportChart chart, Map<String, Map<String, String>> surfaceMarks) {
        return dataUri(chartImage(chart, surfaceMarks));
    }

    static BufferedImage chartImage(PassportChart chart, Map<String, Map<String, String>> surfaceMarks) {
        Tooth[] all = layout(chart.dentition());
        // Cropped to the teeth, so a child's smaller arches fill the same page as an adult's.
        Rectangle2D frame = frameOf(all);
        double scale = PRINT_WIDTH_PX / frame.getWidth();
        BufferedImage image = new BufferedImage((int) PRINT_WIDTH_PX, (int) Math.ceil(frame.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            prepare(g);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.scale(scale, scale);
            g.translate(-frame.getX(), -frame.getY());

            Map<String, PassportChartTooth> byFdi = new HashMap<>();
            for (PassportChartTooth t : chart.teeth()) byFdi.put(t.fdi(), t);

            for (Tooth tooth : all) {
                PassportChartTooth state = byFdi.get(tooth.fdi());
                String status = state == null ? "present" : state.status();
                fill(g, tooth.outline(), status);

                g.setColor(LINE_INK);
                g.setStroke(new BasicStroke(0.7f));
                for (Path2D detail : tooth.details()) g.draw(detail);

                outline(g, tooth.outline(), state);
                Map<String, String> marks = surfaceMarks.get(tooth.fdi());
                if (marks != null && !marks.isEmpty()) surfaceMark(g, tooth, marks);

                text(g, tooth.fdi(), bold(9.2f), Color.BLACK, tooth.labelX(), tooth.labelY(), false);
                if (state != null && !state.keys().isEmpty()) keys(g, all, tooth, state.keys());
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Every tooth and its number, with room around them for the letters on the inside and the outer numbers. */
    static Rectangle2D frameOf(Tooth[] teeth) {
        Rectangle2D frame = null;
        for (Tooth tooth : teeth) {
            Rectangle2D box = tooth.outline().getBounds2D();
            frame = frame == null ? box : frame.createUnion(box);
            frame.add(tooth.labelX(), tooth.labelY());
            frame.add(tooth.labelX() + 12, tooth.labelY() - 9);
        }
        frame.setRect(frame.getX() - 6, frame.getY() - 6, frame.getWidth() + 12, frame.getHeight() + 12);
        return frame;
    }

    /** One legend swatch, drawn with the same marks as the chart. Cached: there are only a few. */
    static String swatchDataUri(String group, String symbol) {
        return SWATCHES.computeIfAbsent(group + "|" + symbol, k -> dataUri(swatch(group, symbol)));
    }

    private static BufferedImage swatch(String group, String symbol) {
        double size = 16;
        BufferedImage image = new BufferedImage((int) (size * SCALE), (int) (size * SCALE), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            prepare(g);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.scale(SCALE, SCALE);
            Shape box = new RoundRectangle2D.Double(1.5, 1.5, size - 3, size - 3, 4, 4);
            switch (symbol) {
                case "ring" -> {
                    g.setColor(DONE_INK);
                    g.setStroke(new BasicStroke(2.2f));
                    g.draw(box);
                }
                case "dashed" -> {
                    g.setColor(PLANNED_INK);
                    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {3f, 2f}, 0f));
                    g.draw(box);
                }
                case "dot" -> {
                    g.setColor(CONDITION_SOLID);
                    g.fill(new Ellipse2D.Double(size / 2 - 3.2, size / 2 - 3.2, 6.4, 6.4));
                    g.setColor(LINE_INK);
                    g.setStroke(new BasicStroke(1f));
                    g.draw(box);
                }
                default -> {
                    String family = "DONE".equals(group) ? "existing" : "ABSENT".equals(group) ? "absent" : "condition";
                    patterned(g, box, family, symbol);
                    g.setColor(ink(family));
                    g.setStroke(new BasicStroke(1.4f));
                    g.draw(box);
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /* ── Paint ───────────────────────────────────────────────────────────── */

    /** Status to family and fill geometry, as core/clinical/tooth-status.ts defines them. */
    private static void fill(Graphics2D g, Shape outline, String status) {
        String family;
        String geometry;
        switch (status) {
            case "extracted", "missing" -> { family = "absent"; geometry = "cross"; }
            case "caries" -> { family = "condition"; geometry = "stipple"; }
            case "fracture" -> { family = "condition"; geometry = "jag"; }
            case "impacted" -> { family = "condition"; geometry = "bars"; }
            case "composite", "amalgam" -> { family = "existing"; geometry = "solid"; }
            case "crown", "bridge", "veneer", "implant" -> { family = "existing"; geometry = "hatch"; }
            case "post", "root_canal" -> { family = "existing"; geometry = "endo"; }
            case "deciduous" -> { family = "existing"; geometry = "dotted"; }
            default -> { return; }
        }
        patterned(g, outline, family, geometry);
    }

    private static void patterned(Graphics2D g, Shape area, String family, String geometry) {
        g.setColor(ground(family));
        g.fill(area);
        if ("solid".equals(geometry)) return;

        Shape previous = g.getClip();
        g.clip(area);
        g.setColor(ink(family));
        Rectangle2D box = area.getBounds2D();
        double tile = 4;
        for (double y = Math.floor(box.getMinY() / tile) * tile; y < box.getMaxY(); y += tile) {
            for (double x = Math.floor(box.getMinX() / tile) * tile; x < box.getMaxX(); x += tile) {
                marks(g, geometry, x, y);
            }
        }
        g.setClip(previous);
    }

    /** The marks inside one 4x4 tile: odontogramPatternDefs() on the screen. */
    private static void marks(Graphics2D g, String geometry, double x, double y) {
        switch (geometry) {
            case "hatch" -> {
                g.setStroke(new BasicStroke(0.7f));
                g.draw(new Line2D.Double(x, y + 4, x + 4, y));
            }
            case "endo" -> {
                g.setStroke(new BasicStroke(0.9f));
                g.draw(new Line2D.Double(x + 2, y, x + 2, y + 4));
            }
            case "dotted" -> {
                g.setStroke(new BasicStroke(0.8f));
                g.draw(new Line2D.Double(x + 2, y + 0.4, x + 2, y + 1.6));
                g.draw(new Line2D.Double(x + 2, y + 2.4, x + 2, y + 3.6));
            }
            case "stipple" -> {
                g.fill(new Ellipse2D.Double(x + 0.45, y + 0.45, 1.1, 1.1));
                g.fill(new Ellipse2D.Double(x + 2.45, y + 2.45, 1.1, 1.1));
            }
            case "bars" -> {
                g.setStroke(new BasicStroke(0.8f));
                g.draw(new Line2D.Double(x, y + 1, x + 4, y + 1));
                g.draw(new Line2D.Double(x, y + 3, x + 4, y + 3));
            }
            case "jag" -> {
                g.setStroke(new BasicStroke(1.1f));
                g.draw(new Line2D.Double(x, y, x + 4, y + 4));
            }
            case "cross" -> {
                g.setStroke(new BasicStroke(0.6f));
                g.draw(new Line2D.Double(x, y + 4, x + 4, y));
                g.draw(new Line2D.Double(x, y, x + 4, y + 4));
            }
            default -> { }
        }
    }

    private static Color ground(String family) {
        return switch (family) {
            case "existing" -> EXISTING_GROUND;
            case "absent" -> ABSENT_GROUND;
            default -> CONDITION_GROUND;
        };
    }

    private static Color ink(String family) {
        return switch (family) {
            case "existing" -> EXISTING_INK;
            case "absent" -> ABSENT_INK;
            default -> CONDITION_INK;
        };
    }

    /** Administered treatment rings the tooth in green; work still owed dashes over it, having the last word. */
    private static void outline(Graphics2D g, Shape outline, PassportChartTooth state) {
        if (state != null && state.owed()) {
            if (state.done()) {
                g.setColor(DONE_INK);
                g.setStroke(new BasicStroke(2.6f));
                g.draw(outline);
            }
            g.setColor(PLANNED_INK);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {3f, 2f}, 0f));
            g.draw(outline);
        } else if (state != null && state.done()) {
            g.setColor(DONE_INK);
            g.setStroke(new BasicStroke(2.2f));
            g.draw(outline);
        } else {
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1f));
            g.draw(outline);
        }
    }

    /* ── The five-part mark ──────────────────────────────────────────────── */

    private static final Map<String, double[][]> ZONES = Map.of(
            "top", new double[][] {{1, 1}, {29, 1}, {21, 9}, {9, 9}},
            "bottom", new double[][] {{1, 29}, {29, 29}, {21, 21}, {9, 21}},
            "left", new double[][] {{1, 1}, {9, 9}, {9, 21}, {1, 29}},
            "right", new double[][] {{29, 1}, {21, 9}, {21, 21}, {29, 29}},
            "center", new double[][] {{9, 9}, {21, 9}, {21, 21}, {9, 21}});

    /** Which part of the drawing a surface occupies: core/clinical/tooth-surfaces.ts. */
    static String zoneOf(String fdi, String surface) {
        boolean right = List.of('1', '4', '5', '8').contains(fdi.charAt(0));
        return switch (surface) {
            case "buccal" -> "top";
            case "lingual" -> "bottom";
            case "occlusal", "incisal" -> "center";
            case "mesial" -> right ? "right" : "left";
            case "distal" -> right ? "left" : "right";
            default -> null;
        };
    }

    private static void surfaceMark(Graphics2D g, Tooth tooth, Map<String, String> marks) {
        Rectangle2D box = tooth.outline().getBounds2D();
        double size = Math.max(9, Math.min(box.getWidth(), box.getHeight()) * 0.62);
        AffineTransform previous = g.getTransform();
        g.translate(box.getCenterX() - size / 2, box.getCenterY() - size / 2);
        g.scale(size / 30, size / 30);
        for (Map.Entry<String, double[][]> zone : ZONES.entrySet()) {
            Path2D path = new Path2D.Double();
            double[][] points = zone.getValue();
            path.moveTo(points[0][0], points[0][1]);
            for (int i = 1; i < points.length; i++) path.lineTo(points[i][0], points[i][1]);
            path.closePath();
            String family = marks.get(zone.getKey());
            g.setColor("condition".equals(family) ? CONDITION_SOLID : "existing".equals(family) ? EXISTING_SOLID : Color.WHITE);
            g.fill(path);
            if ("planned".equals(family)) {
                g.setColor(PLANNED_SOLID);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {3f, 2f}, 0f));
            } else {
                g.setColor(LINE_INK);
                g.setStroke(new BasicStroke(1.2f));
            }
            g.draw(path);
        }
        g.setTransform(previous);
    }

    /** What is recorded on each part of a tooth's five-part mark, strongest first. */
    static void addMark(Map<String, Map<String, String>> marks, String fdi, List<String> surfaces, String kind) {
        String family = switch (kind) {
            case "CONDITION" -> "condition";
            case "EXISTING" -> "existing";
            case "TREATMENT_REQUIRED" -> "planned";
            default -> null;
        };
        if (family == null) return;
        Map<String, Integer> rank = Map.of("planned", 1, "existing", 2, "condition", 3);
        Map<String, String> zones = marks.computeIfAbsent(fdi, f -> new HashMap<>());
        for (String surface : surfaces) {
            String zone = zoneOf(fdi, surface);
            if (zone == null) continue;
            String current = zones.get(zone);
            if (current == null || rank.get(family) > rank.get(current)) zones.put(zone, family);
        }
    }

    /* ── Text ────────────────────────────────────────────────────────────── */

    /**
     * The letters go on the inside of the arch, where there is room, square to the arch at that
     * tooth: straight at the centre they would drift past the neighbour and read as its letters.
     * The arch is taken as an ellipse through the teeth, whose normal is simple to work out.
     */
    private static void keys(Graphics2D g, Tooth[] all, Tooth tooth, List<String> keys) {
        Rectangle2D box = tooth.outline().getBounds2D();
        boolean upper = isUpper(tooth.fdi());
        double backY = upper ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        double frontY = upper ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        double halfWidth = 1;
        for (Tooth other : all) {
            if (isUpper(other.fdi()) != upper) continue;
            Rectangle2D b = other.outline().getBounds2D();
            backY = upper ? Math.max(backY, b.getCenterY()) : Math.min(backY, b.getCenterY());
            frontY = upper ? Math.min(frontY, b.getCenterY()) : Math.max(frontY, b.getCenterY());
            halfWidth = Math.max(halfWidth, Math.abs(b.getCenterX() - CENTRE_X));
        }
        double halfHeight = Math.max(Math.abs(backY - frontY), 1);
        double dx = -(box.getCenterX() - CENTRE_X) / (halfWidth * halfWidth);
        // Flattened toward the side: down the arch the neighbour is above and below, not beside.
        double dy = -(box.getCenterY() - backY) / (halfHeight * halfHeight) * 0.55;
        double length = Math.max(Math.hypot(dx, dy), 1e-9);
        dx /= length;
        dy /= length;
        String text = String.join("\u2009", keys);
        Font font = bold(6.2f);
        Rectangle2D size = font.createGlyphVector(g.getFontRenderContext(), text).getVisualBounds();
        double halfWidth2 = size.getWidth() / 2 + 1.5;
        double halfHeight2 = 3.9;
        double reach = Math.abs(dx) * (box.getWidth() / 2 + halfWidth2) + Math.abs(dy) * (box.getHeight() / 2 + halfHeight2) + 1.2;
        double x = box.getCenterX() + dx * reach;
        double y = box.getCenterY() + dy * reach;
        // In a small pill of its own: the letters of neighbouring teeth must not read as one group.
        Shape pill = new RoundRectangle2D.Double(x - halfWidth2, y - halfHeight2, halfWidth2 * 2, halfHeight2 * 2, 3.4, 3.4);
        g.setColor(Color.WHITE);
        g.fill(pill);
        g.setColor(KEY_INK);
        g.setStroke(new BasicStroke(0.45f));
        g.draw(pill);
        text(g, text, font, KEY_INK, x, y + size.getHeight() / 2, true);
    }

    private static boolean isUpper(String fdi) {
        return List.of('1', '2', '5', '6').contains(fdi.charAt(0));
    }

    /** Text drawn as shapes, with a white halo where it may cross a line. No font is needed on the reader's side. */
    private static void text(Graphics2D g, String text, Font font, Color color, double x, double y, boolean centred) {
        GlyphVector glyphs = font.createGlyphVector(g.getFontRenderContext(), text);
        double start = centred ? x - glyphs.getVisualBounds().getWidth() / 2 : x;
        Shape shape = glyphs.getOutline((float) start, (float) y);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
        g.setColor(color);
        g.fill(shape);
    }

    private static Font baseFont;

    private static synchronized Font bold(float size) {
        if (baseFont == null) {
            try (InputStream in = OdontogramRenderer.class.getClassLoader()
                    .getResourceAsStream("net/sf/jasperreports/fonts/dejavu/DejaVuSans-Bold.ttf")) {
                baseFont = in == null ? new Font(Font.SANS_SERIF, Font.BOLD, 10) : Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (IOException | FontFormatException e) {
                baseFont = new Font(Font.SANS_SERIF, Font.BOLD, 10);
            }
        }
        return baseFont.deriveFont(size);
    }

    /* ── The drawing's outlines ──────────────────────────────────────────── */

    static Tooth[] loadForTest(String dentition) {
        return layout(dentition);
    }

    private static Tooth[] layout(String dentition) {
        String name = "child".equals(dentition) ? "child" : "adult";
        return LAYOUTS.computeIfAbsent(name, OdontogramRenderer::load);
    }

    private static Tooth[] load(String name) {
        try (InputStream in = OdontogramRenderer.class.getClassLoader().getResourceAsStream("pdf/odontogram/" + name + ".json")) {
            if (in == null) throw new IllegalStateException("Missing chart outlines: " + name);
            JsonNode teeth = new ObjectMapper().readTree(in).get("teeth");
            List<Tooth> out = new ArrayList<>();
            teeth.fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                List<Path2D> details = new ArrayList<>();
                node.get("details").forEach(d -> details.add(path(d.asText())));
                out.add(new Tooth(entry.getKey(), path(node.get("outline").asText()), List.copyOf(details),
                        node.get("label").get(0).asDouble(), node.get("label").get(1).asDouble()));
            });
            return out.toArray(new Tooth[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Pattern PATH_TOKEN = Pattern.compile("[mMlLcCzZ]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?");

    /** The chart's paths use only m, l, c and z, in both cases. */
    static Path2D path(String d) {
        Path2D.Double path = new Path2D.Double();
        Matcher m = PATH_TOKEN.matcher(d);
        List<String> tokens = new ArrayList<>();
        while (m.find()) tokens.add(m.group());

        char command = 'm';
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;
        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (Character.isLetter(token.charAt(0))) {
                command = token.charAt(0);
                i++;
                if (command == 'z' || command == 'Z') {
                    path.closePath();
                    x = startX;
                    y = startY;
                }
                continue;
            }
            boolean relative = Character.isLowerCase(command);
            switch (Character.toLowerCase(command)) {
                case 'm' -> {
                    x = (relative ? x : 0) + Double.parseDouble(tokens.get(i));
                    y = (relative ? y : 0) + Double.parseDouble(tokens.get(i + 1));
                    i += 2;
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                    // Further pairs after a move are lines, as the SVG specification has it.
                    command = relative ? 'l' : 'L';
                }
                case 'l' -> {
                    x = (relative ? x : 0) + Double.parseDouble(tokens.get(i));
                    y = (relative ? y : 0) + Double.parseDouble(tokens.get(i + 1));
                    i += 2;
                    path.lineTo(x, y);
                }
                case 'c' -> {
                    double ox = relative ? x : 0;
                    double oy = relative ? y : 0;
                    double x1 = ox + Double.parseDouble(tokens.get(i));
                    double y1 = oy + Double.parseDouble(tokens.get(i + 1));
                    double x2 = ox + Double.parseDouble(tokens.get(i + 2));
                    double y2 = oy + Double.parseDouble(tokens.get(i + 3));
                    x = ox + Double.parseDouble(tokens.get(i + 4));
                    y = oy + Double.parseDouble(tokens.get(i + 5));
                    i += 6;
                    path.curveTo(x1, y1, x2, y2, x, y);
                }
                default -> throw new IllegalStateException("Unexpected path command " + command);
            }
        }
        return path;
    }

    private static void prepare(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static String dataUri(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
