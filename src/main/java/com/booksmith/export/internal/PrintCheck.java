package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.media.api.ImageStorage.ImageData;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conferência das ilustrações para impressão (§4.4 do produto): em que tamanho cada uma sai na página do PDF
 * diagramado e quantos pontos por polegada (DPI) isso dá. Gráfica pede 300 DPI; abaixo disso a imagem sai borrada.
 * Calculado na hora, porque depende da diagramação (tamanho da página e margens), não da imagem.
 *
 * <p>No PDF, a imagem sai no tamanho natural (1 pixel = 1/96 de polegada, como no navegador), limitada à largura
 * do texto e a {@value PdfWriter#ILLUSTRATION_MAX_HEIGHT_MM} mm de altura, sem distorcer.
 */
public final class PrintCheck {

    /** O que as gráficas pedem para imagem impressa. */
    static final int MIN_DPI = 300;

    private static final double MM_PER_INCH = 25.4;

    private PrintCheck() {
    }

    /**
     * Uma ilustração no texto.
     *
     * @param chapter  título do capítulo onde ela está
     * @param caption  legenda (vazia = sem legenda)
     * @param widthPx  largura da imagem em pixels (0 = não deu para ler)
     * @param widthMm  largura na página do PDF, em mm
     * @param dpi      pontos por polegada no tamanho impresso
     * @param printable entra no PDF (WebP não entra: sai só a legenda)
     * @param minWidthPx largura mínima, em pixels, para 300 DPI no mesmo tamanho na página
     * @param ok         sai nítida na impressão: entra no PDF e tem 300 DPI ou mais
     */
    public record Illustration(String imageId, String chapter, String caption, int widthPx, int heightPx, double widthMm,
                        double heightMm, int dpi, boolean printable, int minWidthPx, boolean ok, String size) {

        Illustration(String imageId, String chapter, String caption, int widthPx, int heightPx, double widthMm,
                     double heightMm, int dpi, boolean printable, int minWidthPx, String size) {
            this(imageId, chapter, caption, widthPx, heightPx, widthMm, heightMm, dpi, printable, minWidthPx,
                    printable && dpi >= MIN_DPI, size);
        }
    }

    /**
     * {@code textWidthMm}: largura da área de texto na diagramação, o máximo de uma ilustração. {@code needsBleed}:
     * há ilustração até a borda (ou dupla) e a diagramação está sem sangria: na gráfica, ela sairia com filete branco.
     */
    public record Result(int minDpi, double textWidthMm, List<Illustration> illustrations, boolean needsBleed) {
    }

    static Result check(ExportBook book) {
        BookLayout layout = book.layout();
        double maxWidth = PdfWriter.textWidthMm(layout);
        List<Illustration> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ExportBook.Chapter chapter : book.chapters()) {
            List<JsonNode> nodes = new ArrayList<>();
            collect(chapter.document(), nodes);
            for (JsonNode attrs : nodes) {
                String imageId = attrs.get("imageId").stringValue();
                if (!seen.add(chapter.title() + "|" + imageId + "|" + Placement.of(attrs))) {
                    continue;
                }
                JsonNode captionNode = attrs.get("caption");
                String caption = captionNode == null || captionNode.isNull() ? "" : captionNode.stringValue().strip();
                ImageData image = book.illustrations().get(imageId);
                if (image != null) {
                    found.add(measure(imageId, chapter.title(), caption, image, Placement.of(attrs), layout));
                }
            }
        }
        boolean edge = found.stream().anyMatch(i -> "bleed".equals(i.size()) || "spread".equals(i.size()));
        return new Result(MIN_DPI, round(maxWidth), List.copyOf(found), edge && !layout.hasBleed());
    }

    /**
     * Tamanho na página conforme o lugar escolhido ({@link Placement#fit}): sem lugar, o natural (96 px por
     * polegada) limitado à largura do texto e à altura máxima; até a borda e dupla, o que cobre a página (o DPI conta
     * a imagem inteira, mesmo a parte cortada).
     */
    static Illustration measure(String imageId, String chapter, String caption, ImageData image, Placement placement,
                                BookLayout layout) {
        int[] size = pixels(image.bytes());
        boolean printable = PdfWriter.embeds(image.contentType());
        String where = placement.css();
        if (size[0] == 0 || size[1] == 0) {
            return new Illustration(imageId, chapter, caption, 0, 0, 0, 0, 0, printable, 0, where);
        }
        Placement.Fit fit = placement.fit(size[0], size[1], layout, !caption.isEmpty());
        double widthMm = fit.widthMm();
        double heightMm = fit.heightMm();
        // + 0,001: a imagem no tamanho natural dá 96 exatos, não 95,9999
        int dpi = (int) Math.floor(size[0] / (widthMm / MM_PER_INCH) + 0.001);
        int minWidthPx = (int) Math.ceil(widthMm / MM_PER_INCH * MIN_DPI);
        return new Illustration(imageId, chapter, caption, size[0], size[1], round(widthMm), round(heightMm), dpi,
                printable, minWidthPx, where);
    }

    /** Largura e altura em pixels, lendo só o cabeçalho; {0, 0} se o formato não é lido (ex.: WebP). */
    static int[] pixels(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = input == null ? java.util.Collections.emptyIterator()
                    : ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return new int[] {0, 0};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return new int[] {0, 0};
        }
    }

    private static void collect(JsonNode node, List<JsonNode> found) {
        JsonNode attrs = node.get("attrs");
        if ("illustration".equals(BookHtml.type(node))
                && attrs != null && attrs.get("imageId") != null && !attrs.get("imageId").isNull()) {
            found.add(attrs);
        }
        JsonNode content = node.get("content");
        if (content != null) {
            content.forEach(child -> collect(child, found));
        }
    }

    private static double round(double mm) {
        return Math.round(mm * 10) / 10.0;
    }
}
