package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

/**
 * O livro pronto em PDF (miolo diagramado fora do app): cada página vira uma imagem de 300 DPI, a qualidade que a
 * gráfica pede. Uma página bem mais larga que as outras (ou que a proporção de uma página de livro) é uma página dupla,
 * as duas do livro aberto numa folha só, como muitos programas exportam livros ilustrados. O tamanho do livro (o corte)
 * sai da página simples: TrimBox, se o PDF tiver, senão a página inteira; com a sangria em volta, a diagramação liga a
 * sangria.
 */
final class PdfBookReader {

    /** Livro inteiro, não um panfleto: acima disso, é melhor diagramar no app. */
    static final int MAX_PAGES = 200;
    static final int DPI = 300;
    /** As imagens da biblioteca têm até 5 MB; a página sai em JPEG, com a qualidade baixando até caber. */
    static final long MAX_IMAGE_BYTES = 4_900_000;
    private static final float POINTS_PER_MM = 72f / 25.4f;

    private PdfBookReader() {
    }

    /** Uma página (ou dupla) do livro, já em JPEG. */
    record RenderedPage(int number, boolean spread, byte[] jpeg, int widthPx, int heightPx) {
    }

    /** O tamanho do livro (corte de uma página simples, em mm) e se o PDF tem sangria em volta. */
    record Size(double widthMm, double heightMm, boolean bleed) {
    }

    /**
     * Lê o PDF e entrega as páginas uma a uma (sem guardar todas na memória: uma página dupla de 300 DPI tem dezenas
     * de MB de pixels).
     *
     * @return o tamanho do livro
     * @throws ApiException 422 {@code pdf_invalid}, {@code pdf_protected}, {@code pdf_too_many_pages}
     */
    static Size read(File pdf, Consumer<RenderedPage> pages) {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(pdf), null, null, null,
                IOUtils.createTempFileOnlyStreamCache())) {
            int count = document.getNumberOfPages();
            if (count == 0) {
                throw ApiException.unprocessable("pdf_invalid", "O PDF não tem páginas.");
            }
            if (count > MAX_PAGES) {
                throw ApiException.unprocessable("pdf_too_many_pages",
                        "O PDF tem %d páginas; o limite é %d.".formatted(count, MAX_PAGES));
            }
            double singleAspect = singleAspect(document);
            Size size = size(document, singleAspect);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int i = 0; i < count; i++) {
                PDPage page = document.getPage(i);
                boolean spread = isSpread(box(page), singleAspect);
                BufferedImage image = renderer.renderImageWithDPI(i, DPI, ImageType.RGB);
                pages.accept(new RenderedPage(i + 1, spread, jpeg(image), image.getWidth(), image.getHeight()));
                image.flush();
            }
            return size;
        } catch (InvalidPasswordException e) {
            throw ApiException.unprocessable("pdf_protected", "O PDF tem senha. Salve uma cópia sem senha e envie de novo.")
                    .causedBy(e);
        } catch (IOException e) {
            throw ApiException.unprocessable("pdf_invalid", "Não conseguimos abrir este PDF.").causedBy(e);
        }
    }

    /** A área de uma página: o corte (TrimBox), se o PDF disser, senão a página inteira. */
    private static PDRectangle box(PDPage page) {
        PDRectangle trim = page.getTrimBox();
        return trim == null ? page.getMediaBox() : trim;
    }

    /**
     * A proporção (largura ÷ altura) de uma página simples: a da página mais estreita do PDF. Num PDF todo em duplas
     * (capa à parte), a dupla tem o dobro da largura de uma página.
     */
    static double singleAspect(PDDocument document) {
        double narrowest = Double.MAX_VALUE;
        for (PDPage page : document.getPages()) {
            PDRectangle box = box(page);
            narrowest = Math.min(narrowest, box.getWidth() / box.getHeight());
        }
        // Todas as páginas largas como uma dupla de livro em pé (ex.: 34 × 24): a simples é a metade
        return narrowest > 1.25 ? narrowest / 2 : narrowest;
    }

    /** Dupla: bem mais larga que a página simples (1,6 vezes ou mais). */
    static boolean isSpread(PDRectangle box, double singleAspect) {
        return box.getWidth() / box.getHeight() >= singleAspect * 1.6;
    }

    /** O corte de uma página simples em mm, e se há sangria (a página inteira passa do corte em 2 mm ou mais). */
    private static Size size(PDDocument document, double singleAspect) {
        PDPage first = document.getPage(0);
        PDRectangle trim = box(first);
        PDRectangle media = first.getMediaBox();
        double height = trim.getHeight() / POINTS_PER_MM;
        double width = height * singleAspect;
        boolean bleed = (media.getHeight() - trim.getHeight()) / POINTS_PER_MM >= 2;
        return new Size(Math.round(width * 10) / 10.0, Math.round(height * 10) / 10.0, bleed);
    }

    /** JPEG com a qualidade mais alta que caiba no limite da biblioteca. */
    static byte[] jpeg(BufferedImage image) {
        for (float quality : new float[] {0.9f, 0.82f, 0.72f, 0.6f, 0.5f}) {
            byte[] bytes = encode(image, quality);
            if (bytes.length <= MAX_IMAGE_BYTES) {
                return bytes;
            }
        }
        throw ApiException.unprocessable("pdf_page_too_large",
                "Uma página do PDF ficou grande demais como imagem. Exporte o PDF com imagens menos pesadas.");
    }

    private static byte[] encode(BufferedImage image, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), params);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
