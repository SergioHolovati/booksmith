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

    /** Miniatura (a tela de organizar o livro de texto): pequena, em JPEG, mais páginas por pedido. */
    private static final float THUMB_DPI = 36;
    static final int MAX_THUMBS = 16;

    private PdfPreview() {
    }

    /** @param number a partir de 1 */
    public record Page(int number, String image) {
    }

    public record Preview(int pageCount, List<Page> pages) {
    }

    /**
     * Um trecho do livro de texto e as páginas dele no PDF da prévia.
     *
     * @param kind      ver {@link PdfWriter.Block}
     * @param firstPage a página onde começa, a partir de 1 (a capa conta)
     * @param pageCount quantas páginas
     */
    public record Block(String kind, String ref, String title, int firstPage, int pageCount) {
    }

    /** As páginas do livro de texto, trecho a trecho, na ordem do PDF. */
    public record PageMap(int pageCount, List<Block> blocks) {
    }

    /**
     * Onde começa cada trecho ({@code blocks}, na ordem dos marcadores que {@link PdfWriter#write(ExportBook, List)}
     * pôs no PDF) e quantas páginas tem (até o começo do próximo).
     */
    static PageMap map(byte[] pdf, List<PdfWriter.Block> blocks) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int total = document.getNumberOfPages();
            List<Integer> starts = new ArrayList<>();
            var outline = document.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                for (var item : outline.children()) {
                    var page = item.findDestinationPage(document);
                    starts.add(page == null ? -1 : document.getPages().indexOf(page) + 1);
                }
            }
            List<Block> result = new ArrayList<>();
            for (int i = 0; i < blocks.size() && i < starts.size(); i++) {
                int first = starts.get(i);
                if (first < 1) {
                    continue;
                }
                int next = total + 1;
                for (int j = i + 1; j < starts.size(); j++) {
                    if (starts.get(j) >= first) {
                        next = starts.get(j);
                        break;
                    }
                }
                PdfWriter.Block block = blocks.get(i);
                result.add(new Block(block.kind(), block.ref(), block.title(), first, Math.max(next - first, 1)));
            }
            return new PageMap(total, result);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        return render(pdf, from, count, false);
    }

    /** Idem; {@code thumbs}: miniaturas pequenas em JPEG (até {@link #MAX_THUMBS} por pedido). */
    static Preview render(byte[] pdf, int from, int count, boolean thumbs) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int total = document.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(document);
            List<Page> pages = new ArrayList<>();
            int first = Math.max(from, 1);
            int last = Math.min(first + Math.clamp(count, 1, thumbs ? MAX_THUMBS : MAX_PAGES) - 1, total);
            String format = thumbs ? "jpeg" : "png";
            for (int number = first; number <= last; number++) {
                ByteArrayOutputStream image = new ByteArrayOutputStream();
                ImageIO.write(renderer.renderImageWithDPI(number - 1, thumbs ? THUMB_DPI : DPI, ImageType.RGB), format, image);
                pages.add(new Page(number, "data:image/" + format + ";base64,"
                        + Base64.getEncoder().encodeToString(image.toByteArray())));
            }
            return new Preview(total, pages);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
