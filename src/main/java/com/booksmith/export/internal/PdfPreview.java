package com.booksmith.export.internal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Prévia página a página (§4.7 do produto): as páginas do PDF de verdade, como imagens, para ver no celular
 * (onde o navegador não mostra PDF dentro da página).
 */
public final class PdfPreview {

    /** Resolução da imagem: uma página A5 sai com uns 640 px de largura, nítida numa tela de celular. */
    private static final float DPI = 110;

    /** Páginas por pedido, para a resposta não pesar. */
    static final int MAX_PAGES = 4;

    private PdfPreview() {
    }

    /** @param number a partir de 1 */
    public record Page(int number, String image) {
    }

    public record Preview(int pageCount, List<Page> pages) {
    }

    static int pageCount(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** As páginas {@code from} até {@code from + count - 1} (a partir de 1); fora do livro, a lista vem menor ou vazia. */
    static Preview render(byte[] pdf, int from, int count) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int total = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);
            List<Page> pages = new ArrayList<>();
            int first = Math.max(from, 1);
            int last = Math.min(first + Math.clamp(count, 1, MAX_PAGES) - 1, total);
            for (int number = first; number <= last; number++) {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(renderer.renderImageWithDPI(number - 1, DPI, ImageType.RGB), "png", png);
                pages.add(new Page(number, "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray())));
            }
            return new Preview(total, pages);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
