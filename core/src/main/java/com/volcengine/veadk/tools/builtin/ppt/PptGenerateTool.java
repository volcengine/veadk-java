/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package com.volcengine.veadk.tools.builtin.ppt;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;

/** Creates a PPTX deck and saves it with the current ADK artifact service. */
public final class PptGenerateTool extends BaseTool {

    private static final int MAX_SLIDES = 20;
    private static final int MAX_BULLETS = 7;
    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[^\\p{L}\\p{N}._-]+");

    public PptGenerateTool() {
        super(
                "ppt_generate",
                "Create a PowerPoint deck from flat Markdown and attach it to the conversation.");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        Map<String, Schema> properties = new LinkedHashMap<>();
        for (String name : List.of("title", "deck_markdown", "subtitle", "theme", "filename")) {
            properties.put(name, Schema.builder().type("STRING").build());
        }
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(
                                Schema.builder()
                                        .type("OBJECT")
                                        .properties(properties)
                                        .required("title", "deck_markdown")
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> generate(args, toolContext));
    }

    private Map<String, Object> generate(Map<String, Object> args, ToolContext toolContext) {
        String title = clean(args.get("title"), 160);
        List<SlideSpec> slides = parseSlides(string(args.get("deck_markdown")));
        if (title.isEmpty()) return Map.of("error", "title is required");
        if (slides.isEmpty()) {
            return Map.of("error", "deck_markdown must contain a content slide starting with ##");
        }
        if (toolContext == null)
            return Map.of("error", "tool context with artifact service is required");

        String subtitle = clean(args.get("subtitle"), 240);
        Theme theme = Theme.resolve(string(args.get("theme")));
        String filename = safeFilename(string(args.get("filename")), title);
        String previewName = filename.substring(0, filename.length() - 5) + ".preview.png";
        try {
            byte[] deck = createDeck(title, subtitle, slides, theme);
            byte[] preview = createPreview(title, subtitle, theme);
            toolContext
                    .saveArtifact(previewName, Part.fromBytes(preview, "image/png"))
                    .blockingAwait();
            toolContext
                    .saveArtifact(
                            filename,
                            Part.fromBytes(
                                    deck,
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                    .blockingAwait();
            return Map.of(
                    "status",
                    "created",
                    "filename",
                    filename,
                    "preview_filename",
                    previewName,
                    "slide_count",
                    slides.size() + 1);
        } catch (RuntimeException | IOException failure) {
            return Map.of("error", "PPT generation failed: " + safeMessage(failure));
        }
    }

    private static byte[] createDeck(
            String title, String subtitle, List<SlideSpec> slides, Theme theme) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            show.setPageSize(new java.awt.Dimension(1280, 720));
            XSLFSlide cover = show.createSlide();
            cover.getBackground().setFillColor(theme.cover());
            addText(cover, title, 88, 190, 1080, 160, 48, true, theme.coverText());
            if (!subtitle.isEmpty()) {
                addText(cover, subtitle, 92, 390, 1000, 90, 24, false, theme.coverMuted());
            }
            for (int index = 0; index < slides.size(); index++) {
                SlideSpec item = slides.get(index);
                XSLFSlide slide = show.createSlide();
                slide.getBackground().setFillColor(theme.canvas());
                addText(slide, item.title(), 76, 55, 1040, 70, 36, true, theme.title());
                addText(
                        slide,
                        String.format("%02d", index + 2),
                        1130,
                        62,
                        80,
                        35,
                        16,
                        true,
                        theme.accent());
                int top = 150;
                if (!item.summary().isEmpty()) {
                    addText(slide, item.summary(), 78, top, 1080, 70, 22, true, theme.body());
                    top += 85;
                }
                XSLFTextBox bullets = slide.createTextBox();
                bullets.setAnchor(new Rectangle2D.Double(92, top, 1060, 610 - top));
                for (String bullet : item.bullets()) {
                    XSLFTextParagraph paragraph = bullets.addNewTextParagraph();
                    paragraph.setBullet(true);
                    paragraph.setLeftMargin(24d);
                    XSLFTextRun run = paragraph.addNewTextRun();
                    run.setText(bullet);
                    run.setFontSize(item.bullets().size() >= 6 ? 20d : 23d);
                    run.setFontColor(theme.body());
                }
                if (!item.sources().isEmpty()) {
                    addText(
                            slide,
                            "Sources: " + String.join(" | ", item.sources()),
                            78,
                            665,
                            1080,
                            30,
                            9,
                            false,
                            theme.muted());
                }
            }
            show.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] createPreview(String title, String subtitle, Theme theme)
            throws IOException {
        BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(theme.cover());
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(theme.coverText());
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 54));
            graphics.drawString(title, 88, 310);
            if (!subtitle.isEmpty()) {
                graphics.setColor(theme.coverMuted());
                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 25));
                graphics.drawString(subtitle, 92, 405);
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static void addText(
            XSLFSlide slide,
            String text,
            double left,
            double top,
            double width,
            double height,
            double fontSize,
            boolean bold,
            Color color) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(left, top, width, height));
        XSLFTextParagraph paragraph = box.addNewTextParagraph();
        paragraph.setTextAlign(TextAlign.LEFT);
        XSLFTextRun run = paragraph.addNewTextRun();
        run.setText(text);
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontColor(color);
    }

    static List<SlideSpec> parseSlides(String markdown) {
        List<SlideSpec> result = new ArrayList<>();
        MutableSlide current = null;
        for (String raw : markdown.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("## ")) {
                if (current != null) result.add(current.freeze());
                if (result.size() == MAX_SLIDES) break;
                current = new MutableSlide(clean(line.substring(3), 120));
            } else if (current != null && startsWithSources(line)) {
                int separator = line.startsWith("来源：") ? line.indexOf('：') : line.indexOf(':');
                String[] values = line.substring(separator + 1).split("\\|");
                for (String value : values) {
                    if (current.sources.size() < 12) current.sources.add(clean(value, 500));
                }
            } else if (current != null && line.matches("^[-*•]\\s+.*")) {
                if (current.bullets.size() < MAX_BULLETS) {
                    current.bullets.add(clean(line.substring(2), 220));
                }
            } else if (current != null && current.summary.isEmpty()) {
                current.summary = clean(line, 240);
            } else if (current != null && current.bullets.size() < MAX_BULLETS) {
                current.bullets.add(clean(line, 220));
            }
        }
        if (current != null && result.size() < MAX_SLIDES) result.add(current.freeze());
        return result.stream().filter(slide -> !slide.title().isEmpty()).toList();
    }

    private static boolean startsWithSources(String line) {
        String lowered = line.toLowerCase(Locale.ROOT);
        return lowered.startsWith("sources:") || line.startsWith("来源：");
    }

    private static String safeFilename(String requested, String title) {
        String leaf = requested == null ? "" : requested.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).strip();
        if (leaf.isEmpty()) leaf = UNSAFE_FILENAME.matcher(title).replaceAll("-");
        leaf = UNSAFE_FILENAME.matcher(leaf).replaceAll("-");
        if (leaf.length() > 100) leaf = leaf.substring(0, 100);
        if (leaf.isBlank()) leaf = "presentation";
        return leaf.toLowerCase(Locale.ROOT).endsWith(".pptx") ? leaf : leaf + ".pptx";
    }

    private static String clean(Object value, int maxLength) {
        String cleaned = string(value).replaceAll("\\s+", " ").strip();
        return cleaned.substring(0, Math.min(maxLength, cleaned.length()));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safeMessage(Exception failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    record SlideSpec(String title, String summary, List<String> bullets, List<String> sources) {}

    private static final class MutableSlide {
        private final String title;
        private String summary = "";
        private final List<String> bullets = new ArrayList<>();
        private final List<String> sources = new ArrayList<>();

        private MutableSlide(String title) {
            this.title = title;
        }

        private SlideSpec freeze() {
            return new SlideSpec(title, summary, List.copyOf(bullets), List.copyOf(sources));
        }
    }

    private record Theme(
            Color canvas,
            Color cover,
            Color title,
            Color body,
            Color muted,
            Color accent,
            Color coverText,
            Color coverMuted) {
        private static Theme resolve(String requested) {
            return switch (requested.toLowerCase(Locale.ROOT)) {
                case "dark" ->
                        colors(
                                "10151E", "090D14", "F5F7FA", "CCD4E0", "8D9AAF", "74A7FF",
                                "FFFFFF", "AAB6C8");
                case "warm" ->
                        colors(
                                "FBF6EF", "41281E", "3D2A24", "604A42", "8C7469", "D66A3A",
                                "FFF8F2", "E7CFC2");
                case "green" ->
                        colors(
                                "F2F8F5", "12372B", "173B31", "36594F", "6D887F", "2B8A68",
                                "F8FFFC", "BFD9CF");
                default ->
                        colors(
                                "F4F7FB", "0B1F3A", "10233F", "344760", "6B7C93", "2F6FED",
                                "FFFFFF", "C9D8F0");
            };
        }

        private static Theme colors(String... values) {
            Color[] colors =
                    java.util.Arrays.stream(values)
                            .map(value -> Color.decode("#" + value))
                            .toArray(Color[]::new);
            return new Theme(
                    colors[0], colors[1], colors[2], colors[3], colors[4], colors[5], colors[6],
                    colors[7]);
        }
    }
}
