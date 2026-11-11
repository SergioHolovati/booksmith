package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.BookLayout;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Lê a capa que o autor fez fora do app, em PDF: mede a primeira página e decide se é só a frente (em pé) ou a
 * capa aberta (deitada: contracapa + lombada + frente), e desenha a frente como imagem para o e-book.
 */
final class CoverPdfReader {

    private static final double MM_PER_POINT = 25.4 / 72;

    /** Largura da imagem da frente: perto dos 1600 px da capa de e-book. */
    private static final int FRONT_WIDTH_PX = 1600;

    private CoverPdfReader() {
    }

    /**
     * @param kind       {@code front} (só a frente) ou {@code wrap} (capa aberta)
     * @param frontImage a frente em JPEG, como data URL (na capa aberta, a parte da direita)
     */
    record Result(String kind, double widthMm, double heightMm, int pages, String frontImage) {
    }

    /** @throws ApiException 422 {@code cover_pdf_invalid} ou {@code cover_pdf_protected} */
    static Result read(byte[] pdf, BookLayout layout) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (document.getNumberOfPages() == 0) {
                throw invalid("O PDF não tem páginas.");
            }
            PDRectangle box = document.getPage(0).getMediaBox();
            // Página girada no PDF: a medida que vale é a de quem olha
            int rotation = document.getPage(0).getRotation();
            boolean turned = rotation == 90 || rotation == 270;
            double width = (turned ? box.getHeight() : box.getWidth()) * MM_PER_POINT;
            double height = (turned ? box.getWidth() : box.getHeight()) * MM_PER_POINT;
            String kind = width > height ? "wrap" : "front";

            float dpi = (float) (FRONT_WIDTH_PX / (frontWidthMm(kind, width, height, layout) / 25.4));
            BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, Math.min(dpi, 400), ImageType.RGB);
            BufferedImage front = page;
            if ("wrap".equals(kind)) {
                // A frente é a parte da direita, com a proporção do livro (a altura da capa inclui a sangria)
                int frontPx = (int) Math.round(page.getWidth() * frontWidthMm(kind, width, height, layout) / width);
                front = page.getSubimage(page.getWidth() - frontPx, 0, frontPx, page.getHeight());
            }
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            ImageIO.write(front, "jpg", jpeg);
            return new Result(kind, round(width), round(height), document.getNumberOfPages(),
                    "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg.toByteArray()));
        } catch (InvalidPasswordException e) {
            throw ApiException.unprocessable("cover_pdf_protected",
                    "O PDF está protegido por senha. Exporte de novo sem proteção.").causedBy(e);
        } catch (IOException e) {
            throw invalid("Não conseguimos abrir o PDF. Ele pode estar corrompido.").causedBy(e);
        }
    }

    /** Na capa aberta, a frente tem a proporção do livro (largura ÷ altura do miolo) na altura da capa. */
    private static double frontWidthMm(String kind, double width, double height, BookLayout layout) {
        if ("front".equals(kind)) {
            return width;
        }
        return Math.min(width, height * layout.widthMm() / layout.heightMm());
    }

    private static double round(double mm) {
        return Math.round(mm * 10) / 10.0;
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("cover_pdf_invalid", message);
    }
}
