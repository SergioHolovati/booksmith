package com.booksmith.export.internal;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Modelo (gabarito) da capa para quem vai fazer a capa fora do app: um PDF no tamanho exato das medidas de
 * {@link PrintCover.Spec}. Página 1, a capa aberta (deitada): sangria, orelhas, contracapa, lombada e frente, com o
 * corte, as dobras, a área segura e o lugar do código de barras, cada parte com as medidas. Página 2, só a frente
 * (em pé), para quem faz só a frente e usa a lombada e a contracapa do app. Sem orelhas, a página leva o TrimBox e o
 * BleedBox certos: o PDF exportado por cima do modelo já diz à importação onde é o corte.
 */
final class PrintCoverTemplate {

    private static final double MM_PER_INCH = 25.4;
    /** Área segura: texto e rosto a 6 mm do corte (a mesma do desenho da contracapa no app). */
    static final double SAFE_MM = 6;
    /** O quadro do código de barras e onde a contracapa do app o desenha (a 4 mm da área segura). */
    static final double BARCODE_W_MM = 38;
    static final double BARCODE_H_MM = 24;
    private static final double BARCODE_INSET_MM = 4;

    private static final Color BLEED = new Color(253, 226, 226);
    private static final Color TRIM = new Color(214, 40, 40);
    private static final Color FOLD = new Color(30, 100, 220);
    private static final Color SAFE = new Color(20, 150, 80);
    private static final Color LABEL = new Color(120, 120, 120);
    private static final Color INK = new Color(40, 40, 40);

    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private PrintCoverTemplate() {
    }

    /**
     * @param title   o título do livro (no quadro de instruções)
     * @param profile o destino, como o autor vê ("KDP, papel branco"…)
     */
    static byte[] pdf(PrintCover.Spec spec, String title, String profile) {
        try (PDDocument document = new PDDocument()) {
            wrapPage(document, spec, title, profile);
            frontPage(document, spec);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void wrapPage(PDDocument document, PrintCover.Spec spec, String title, String profile)
            throws IOException {
        double b = spec.bleedMm();
        double f = spec.flapMm();
        double w = spec.trimWidthMm();
        double h = spec.trimHeightMm();
        double s = spec.spineMm();
        double width = spec.widthMm();
        double height = spec.heightMm();
        PDPage page = new PDPage(new PDRectangle(pt(width), pt(height)));
        if (f == 0) {
            // A importação lê o corte daqui (com orelhas, a conta da lombada pelo TrimBox não serve)
            page.setTrimBox(new PDRectangle(pt(b), pt(b), pt(width - 2 * b), pt(h)));
            page.setBleedBox(new PDRectangle(pt(width), pt(height)));
        }
        document.addPage(page);
        double back = b + f;
        double spine = back + w;
        double front = spine + s;
        try (PDPageContentStream c = new PDPageContentStream(document, page)) {
            background(c, width, height, b);
            // Área segura de cada painel
            safe(c, back, b, w, h);
            safe(c, front, b, w, h);
            if (f > 0) {
                safe(c, b, b, f, h);
                safe(c, front + w, b, f, h);
            }
            // Código de barras: canto de baixo, à direita da contracapa
            double barX = spine - SAFE_MM - BARCODE_INSET_MM - BARCODE_W_MM;
            double barY = b + SAFE_MM + BARCODE_INSET_MM;
            dashedRect(c, LABEL, barX, barY, BARCODE_W_MM, BARCODE_H_MM, 1.5f);
            centered(c, REGULAR, 6.5f, LABEL, barX + BARCODE_W_MM / 2, barY + BARCODE_H_MM / 2 + 1, "Código de barras");
            centered(c, REGULAR, 6.5f, LABEL, barX + BARCODE_W_MM / 2, barY + BARCODE_H_MM / 2 - 3,
                    mm(BARCODE_W_MM) + " × " + mm(BARCODE_H_MM) + " mm");
            // Dobras
            List<Double> folds = new ArrayList<>(List.of(spine, front));
            if (f > 0) {
                folds.add(back);
                folds.add(front + w);
            }
            for (double x : folds) {
                dashedLine(c, FOLD, x, 0, x, height);
            }
            trimLine(c, b, b, width - 2 * b, h);

            // Nomes e medidas de cada painel
            panelLabel(c, "CONTRACAPA", back + w / 2, b + h * 0.42, w, h);
            panelLabel(c, "FRENTE", front + w / 2, b + h / 2, w, h);
            if (f > 0) {
                panelLabel(c, "ORELHA", b + f / 2, b + h / 2, f, h);
                panelLabel(c, "ORELHA", front + w + f / 2, b + h / 2, f, h);
            }
            spineLabel(c, spine + s / 2, b + h / 2, s);
            instructions(c, spec, title, profile, back + SAFE_MM + 2, b + h - SAFE_MM - 4, w - 2 * SAFE_MM - 4);
        }
    }

    private static void frontPage(PDDocument document, PrintCover.Spec spec) throws IOException {
        double b = spec.bleedMm();
        double w = spec.trimWidthMm();
        double h = spec.trimHeightMm();
        PDPage page = new PDPage(new PDRectangle(pt(w + 2 * b), pt(h + 2 * b)));
        document.addPage(page);
        try (PDPageContentStream c = new PDPageContentStream(document, page)) {
            background(c, w + 2 * b, h + 2 * b, b);
            safe(c, b, b, w, h);
            trimLine(c, b, b, w, h);
            panelLabel(c, "FRENTE", b + w / 2, b + h / 2, w, h);
            centered(c, REGULAR, 7.5f, INK, b + w / 2, b + h * 0.3,
                    "Só a frente: com sangria, %s × %s mm".formatted(mm(w + 2 * b), mm(h + 2 * b)));
            centered(c, REGULAR, 7.5f, INK, b + w / 2, b + h * 0.3 - 4.5,
                    "A lombada e a contracapa saem do Booksmith.");
        }
    }

    private static void background(PDPageContentStream c, double width, double height, double bleed)
            throws IOException {
        c.setNonStrokingColor(BLEED);
        c.addRect(0, 0, pt(width), pt(height));
        c.fill();
        c.setNonStrokingColor(Color.WHITE);
        c.addRect(pt(bleed), pt(bleed), pt(width - 2 * bleed), pt(height - 2 * bleed));
        c.fill();
    }

    private static void safe(PDPageContentStream c, double x, double y, double w, double h) throws IOException {
        if (w <= 2 * SAFE_MM || h <= 2 * SAFE_MM) {
            return;
        }
        dashedRect(c, SAFE, x + SAFE_MM, y + SAFE_MM, w - 2 * SAFE_MM, h - 2 * SAFE_MM, 1f);
    }

    private static void trimLine(PDPageContentStream c, double x, double y, double w, double h) throws IOException {
        c.setStrokingColor(TRIM);
        c.setLineWidth(0.8f);
        c.setLineDashPattern(new float[0], 0);
        c.addRect(pt(x), pt(y), pt(w), pt(h));
        c.stroke();
    }

    private static void dashedRect(PDPageContentStream c, Color color, double x, double y, double w, double h,
                                   float dash) throws IOException {
        c.setStrokingColor(color);
        c.setLineWidth(0.6f);
        c.setLineDashPattern(new float[]{dash * 2, dash * 2}, 0);
        c.addRect(pt(x), pt(y), pt(w), pt(h));
        c.stroke();
        c.setLineDashPattern(new float[0], 0);
    }

    private static void dashedLine(PDPageContentStream c, Color color, double x1, double y1, double x2, double y2)
            throws IOException {
        c.setStrokingColor(color);
        c.setLineWidth(0.8f);
        c.setLineDashPattern(new float[]{6, 4}, 0);
        c.moveTo(pt(x1), pt(y1));
        c.lineTo(pt(x2), pt(y2));
        c.stroke();
        c.setLineDashPattern(new float[0], 0);
    }

    private static void panelLabel(PDPageContentStream c, String name, double cx, double cy, double w, double h)
            throws IOException {
        float size = (float) Math.min(22, Math.max(8, w * 0.9 / Math.max(1, name.length()) * 72 / MM_PER_INCH * 1.5));
        centered(c, BOLD, size, LABEL, cx, cy, name);
        centered(c, REGULAR, Math.max(7f, size * 0.5f), LABEL, cx, cy - size * MM_PER_INCH / 72 * 0.9,
                mm(w) + " × " + mm(h) + " mm");
    }

    /** O nome da lombada de cima para baixo, como se lê o livro em pé na estante. */
    private static void spineLabel(PDPageContentStream c, double cx, double cy, double s) throws IOException {
        String text = "LOMBADA  " + mm(s) + " mm";
        float size = (float) Math.max(4, Math.min(11, s * 0.55 * 72 / MM_PER_INCH));
        float textWidth = BOLD.getStringWidth(printable(BOLD, text)) / 1000 * size;
        c.beginText();
        c.setFont(BOLD, size);
        c.setNonStrokingColor(LABEL);
        // Girada 90° no sentido do relógio: o texto desce
        c.setTextMatrix(new Matrix(0, -1, 1, 0, pt(cx) - size * 0.35f, pt(cy) + textWidth / 2));
        c.showText(printable(BOLD, text));
        c.endText();
    }

    private static void instructions(PDPageContentStream c, PrintCover.Spec spec, String title, String profile,
                                     double x, double top, double width) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Modelo da capa: " + title);
        lines.add(profile + " · " + spec.pages() + " páginas");
        lines.add("Capa aberta: %s × %s mm (com a sangria)".formatted(mm(spec.widthMm()), mm(spec.heightMm())));
        lines.add("Livro: %s × %s mm · lombada %s mm · sangria %s mm".formatted(mm(spec.trimWidthMm()),
                mm(spec.trimHeightMm()), mm(spec.spineMm()), mm(spec.bleedMm())));
        if (spec.flapMm() > 0) {
            lines.add("Orelhas: %s mm cada".formatted(mm(spec.flapMm())));
        }
        lines.add("");
        lines.add("Vermelho contínuo: corte. Rosa: sangria (o fundo vai até a borda).");
        lines.add("Azul tracejado: dobras. Verde: área segura (texto dentro).");
        lines.add(spec.spineText() ? "A lombada comporta texto." : "Lombada fina: melhor sem texto, só a cor.");
        lines.add("");
        lines.add("Desenhe por cima e apague as guias antes de exportar.");
        lines.add("Exporte em PDF, uma página, neste tamanho exato.");
        lines.add("No Booksmith: Capa para gráfica > Importar capa em PDF.");
        float size = (float) Math.max(5.5, Math.min(8, width / 62 * 8));
        double lineMm = size * MM_PER_INCH / 72 * 1.35;
        double y = top - size * MM_PER_INCH / 72;
        for (int i = 0; i < lines.size(); i++) {
            String line = fit(lines.get(i), i == 0 ? BOLD : REGULAR, size, width);
            if (!line.isEmpty()) {
                text(c, i == 0 ? BOLD : REGULAR, size, INK, x, y, line);
            }
            y -= lineMm;
        }
    }

    /** Corta a linha que não cabe (título comprido), com reticências. */
    private static String fit(String line, PDFont font, float size, double widthMm) throws IOException {
        String out = line;
        while (out.length() > 4 && font.getStringWidth(printable(font, out)) / 1000 * size > pt(widthMm)) {
            out = out.substring(0, out.length() - 2);
        }
        return out.equals(line) ? line : out.strip() + "...";
    }

    private static void centered(PDPageContentStream c, PDFont font, float size, Color color, double cx, double y,
                                 String text) throws IOException {
        float width = font.getStringWidth(printable(font, text)) / 1000 * size;
        text(c, font, size, color, cx - width / 2 / 72 * MM_PER_INCH, y, text);
    }

    private static void text(PDPageContentStream c, PDFont font, float size, Color color, double x, double y,
                             String text) throws IOException {
        c.beginText();
        c.setFont(font, size);
        c.setNonStrokingColor(color);
        c.newLineAtOffset(pt(x), pt(y));
        c.showText(printable(font, text));
        c.endText();
    }

    /** A fonte padrão do PDF só tem as letras do Windows-1252: o que não tiver (emoji, outras escritas) sai como "?". */
    static String printable(PDFont font, String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach((cp) -> {
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                out.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                out.append('?');
            }
        });
        return out.toString();
    }

    private static float pt(double mm) {
        return (float) (mm / MM_PER_INCH * 72);
    }

    /** 12.45 → "12,5"; 148.0 → "148". */
    static String mm(double value) {
        String text = String.format(Locale.forLanguageTag("pt-BR"), "%.1f", value);
        return text.endsWith(",0") ? text.substring(0, text.length() - 2) : text;
    }
}
