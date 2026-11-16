package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
import tools.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Onde a ilustração fica na página: o atributo {@code size} do bloco no documento (§4.4 do produto). Sem ele, o
 * comportamento antigo ({@link #AUTO}): tamanho natural, limitado à largura do texto.
 *
 * <ul>
 *   <li>{@link #SMALL}, {@link #MEDIUM}, {@link #WIDE}: 40%, 65% e 100% da largura do texto; as duas primeiras
 *   podem ir à esquerda ou à direita, com o texto em volta;</li>
 *   <li>{@link #PAGE}: página só dela, dentro das margens, inteira (sem cortar);</li>
 *   <li>{@link #BLEED}: página inteira até a borda (cobre a página e corta o que sobra);</li>
 *   <li>{@link #SPREAD}: página dupla, as duas páginas do livro aberto (no PDF; no ePub, uma página).</li>
 * </ul>
 */
enum Placement {
    AUTO(0), SMALL(0.4), MEDIUM(0.65), WIDE(1), PAGE(1), BLEED(1), SPREAD(1);

    /** Fração da largura do texto (as que ficam no meio do texto). */
    final double fraction;

    Placement(double fraction) {
        this.fraction = fraction;
    }

    static Placement of(JsonNode attrs) {
        JsonNode size = attrs == null ? null : attrs.get("size");
        if (size == null || size.isNull() || !size.isString()) {
            return AUTO;
        }
        try {
            return valueOf(size.stringValue().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }

    /** "left", "right" ou "center"; só a pequena e a média ficam de lado. */
    static String align(JsonNode attrs, Placement placement) {
        JsonNode align = attrs == null ? null : attrs.get("align");
        String value = align == null || align.isNull() || !align.isString() ? "center" : align.stringValue();
        boolean side = placement == SMALL || placement == MEDIUM;
        return side && ("left".equals(value) || "right".equals(value)) ? value : "center";
    }

    /** Tem página própria (no PDF e no ePub). */
    boolean ownPage() {
        return this == PAGE || this == BLEED || this == SPREAD;
    }

    /** Vai até a borda da página (sem margem). */
    boolean fullBleed() {
        return this == BLEED || this == SPREAD;
    }

    String css() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * O tamanho da imagem na página do PDF, em mm, e quanto dela fica para fora da caixa (nas que cobrem a página:
     * {@code offsetX/Y} é o que se corta de cada lado). {@code box}: a área que ela ocupa.
     */
    record Fit(double widthMm, double heightMm, double boxWidthMm, double boxHeightMm, double offsetXMm, double offsetYMm) {
    }

    /** Espaço reservado para a legenda na página só da ilustração. */
    static final double CAPTION_SPACE_MM = 12;

    /** Natural: 1 pixel = 1/96 de polegada, como no navegador. */
    private static final double MM_PER_CSS_PX = 25.4 / 96;

    Fit fit(int widthPx, int heightPx, BookLayout layout, boolean caption) {
        double textWidth = PdfWriter.textWidthMm(layout);
        double maxHeight = PdfWriter.ILLUSTRATION_MAX_HEIGHT_MM;
        double ratio = (double) heightPx / widthPx;
        return switch (this) {
            case AUTO -> {
                double w = widthPx * MM_PER_CSS_PX;
                double h = heightPx * MM_PER_CSS_PX;
                double scale = Math.min(1, Math.min(textWidth / w, maxHeight / h));
                yield new Fit(w * scale, h * scale, textWidth, h * scale, 0, 0);
            }
            case SMALL, MEDIUM, WIDE -> {
                double w = textWidth * fraction;
                double h = w * ratio;
                if (h > maxHeight) {
                    w = maxHeight / ratio;
                    h = maxHeight;
                }
                yield new Fit(w, h, textWidth, h, 0, 0);
            }
            case PAGE -> {
                double boxW = textWidth;
                double boxH = PdfWriter.textHeightMm(layout) - (caption ? CAPTION_SPACE_MM : 0);
                double scale = Math.min(boxW / widthPx, boxH / heightPx);
                yield new Fit(widthPx * scale, heightPx * scale, boxW, boxH, 0, 0);
            }
            case BLEED, SPREAD -> {
                double[] page = PdfWriter.pageBoxMm(layout);
                double boxW = this == SPREAD ? page[0] * 2 : page[0];
                double boxH = page[1];
                double scale = Math.max(boxW / widthPx, boxH / heightPx);
                double w = widthPx * scale;
                double h = heightPx * scale;
                yield new Fit(w, h, boxW, boxH, (w - boxW) / 2, (h - boxH) / 2);
            }
        };
    }
}
