package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.BookLayout;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Lê a capa que o autor fez fora do app, em PDF: mede a primeira página, decide se é só a frente (em pé) ou a
 * capa aberta (deitada: contracapa + lombada + frente) e desenha a frente, recortada no corte, como imagem para o
 * app e o e-book.
 * <p>
 * Para achar o corte, basta saber o tamanho do livro: a capa fica no centro da página, e o que sobra em volta
 * (sangria mais a margem das marcas de corte) é igual dos quatro lados. O tamanho vem, nesta ordem: do TrimBox do
 * PDF; do que o autor informou na importação; na frente sozinha, do tamanho que deixa a sobra igual em cima e do
 * lado; e por fim do tamanho da diagramação do livro, se couber. Sem nenhum deles, a frente é a parte da direita
 * na proporção do livro (como antes).
 */
final class CoverPdfReader {

    private static final double MM_PER_POINT = 25.4 / 72;

    /** Largura da imagem da frente: perto dos 1600 px da capa de e-book. */
    private static final int FRONT_WIDTH_PX = 1600;
    /** Diferença aceita entre medidas (o PDF arredonda). */
    static final double TOLERANCE_MM = 1.5;
    /** Sangria mais marcas de corte acima disso não é capa daquele tamanho. */
    static final double MAX_EDGE_MM = 25;

    private CoverPdfReader() {
    }

    /**
     * O que o autor informou na importação (tudo opcional).
     *
     * @param trim    o tamanho do livro para o qual a capa foi feita ({@link BookLayout#TRIMS})
     * @param spineMm a lombada da capa aberta
     * @param bleedMm a sangria
     */
    record Declared(String trim, Double spineMm, Double bleedMm) {

        static final Declared NONE = new Declared(null, null, null);
    }

    /**
     * @param kind       {@code front} (só a frente) ou {@code wrap} (capa aberta)
     * @param frontImage a frente em JPEG, como data URL, recortada no corte quando se sabe o tamanho
     * @param trim       o tamanho do livro da capa, ou null quando não deu para saber
     * @param spineMm    a lombada medida (capa aberta com tamanho conhecido)
     * @param bleedMm    a sangria, quando informada ou dita pelo PDF
     * @param marksMm    a margem das marcas de corte, quando se sabe a sangria
     */
    record Result(String kind, double widthMm, double heightMm, int pages, String frontImage, String trim,
                  Double spineMm, Double bleedMm, Double marksMm) {
    }

    /** Sem nada informado. */
    static Result read(byte[] pdf, BookLayout layout) {
        return read(pdf, layout, Declared.NONE);
    }

    /** @throws ApiException 422 {@code cover_pdf_invalid}, {@code cover_pdf_protected} ou {@code cover_pdf_spec_mismatch} */
    static Result read(byte[] pdf, BookLayout layout, Declared declared) {
        validate(declared);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (document.getNumberOfPages() == 0) {
                throw invalid("O PDF não tem páginas.");
            }
            PDPage first = document.getPage(0);
            PDRectangle media = first.getMediaBox();
            // Página girada no PDF: a medida que vale é a de quem olha
            int rotation = first.getRotation();
            boolean turned = rotation == 90 || rotation == 270;
            double width = (turned ? media.getHeight() : media.getWidth()) * MM_PER_POINT;
            double height = (turned ? media.getWidth() : media.getHeight()) * MM_PER_POINT;
            String kind = width > height ? "wrap" : "front";
            Geometry geometry = geometry(first, turned, kind, width, height, layout, declared);

            float dpi = (float) (FRONT_WIDTH_PX / (geometry.frontW() / 25.4));
            BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, Math.min(dpi, 400), ImageType.RGB);
            double pxPerMm = page.getWidth() / width;
            int x = clamp((int) Math.round(geometry.frontX() * pxPerMm), page.getWidth() - 1);
            int y = clamp((int) Math.round(geometry.frontY() * pxPerMm), page.getHeight() - 1);
            int w = Math.min(page.getWidth() - x, (int) Math.round(geometry.frontW() * pxPerMm));
            int h = Math.min(page.getHeight() - y, (int) Math.round(geometry.frontH() * pxPerMm));
            BufferedImage front = page.getSubimage(x, y, w, h);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            ImageIO.write(front, "jpg", jpeg);
            return new Result(kind, round(width), round(height), document.getNumberOfPages(),
                    "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg.toByteArray()),
                    geometry.trim(), geometry.spineMm(), geometry.bleedMm(), geometry.marksMm());
        } catch (InvalidPasswordException e) {
            throw ApiException.unprocessable("cover_pdf_protected",
                    "O PDF está protegido por senha. Exporte de novo sem proteção.").causedBy(e);
        } catch (IOException e) {
            throw invalid("Não conseguimos abrir o PDF. Ele pode estar corrompido.").causedBy(e);
        }
    }

    /**
     * Onde fica a frente no corte, em mm a partir do canto de cima à esquerda da página.
     */
    record Geometry(String trim, Double spineMm, Double bleedMm, Double marksMm, double frontX, double frontY,
                    double frontW, double frontH) {
    }

    static Geometry geometry(PDPage page, boolean turned, String kind, double width, double height, BookLayout layout,
                             Declared declared) {
        Geometry fromBoxes = turned ? null : fromBoxes(page, kind, width, height, declared);
        if (fromBoxes != null) {
            return fromBoxes;
        }
        boolean wrap = "wrap".equals(kind);
        String trim = declared.trim();
        if (trim == null) {
            trim = wrap ? fitting(layout.trim(), width, height, true) : symmetricTrim(width, height, layout.trim());
        }
        if (trim == null) {
            // Sem saber o tamanho: a frente é a parte da direita na proporção do livro, com a altura toda
            double frontW = wrap ? Math.min(width, height * layout.widthMm() / layout.heightMm()) : width;
            return new Geometry(null, null, null, null, width - frontW, 0, frontW, height);
        }
        double tw = BookLayout.trimWidthMm(trim);
        double th = BookLayout.trimHeightMm(trim);
        double edge = (height - th) / 2;
        if (edge < -TOLERANCE_MM / 2 || edge > MAX_EDGE_MM) {
            throw mismatch("O PDF tem %s mm de altura; uma capa de %s, com sangria, teria entre %s e %s mm. Confira o tamanho do livro."
                    .formatted(mm(height), label(trim), mm(th), mm(th + 2 * MAX_EDGE_MM)));
        }
        edge = Math.max(0, edge);
        Double spine = null;
        if (wrap) {
            spine = round(width - 2 * edge - 2 * tw);
            if (spine < -TOLERANCE_MM / 2) {
                throw mismatch("O PDF tem %s mm de largura: não cabem a contracapa e a frente de %s. Confira o tamanho do livro."
                        .formatted(mm(width), label(trim)));
            }
            spine = Math.max(0, spine);
            if (declared.spineMm() != null && Math.abs(spine - declared.spineMm()) > TOLERANCE_MM) {
                throw mismatch(("Pelo tamanho do PDF, a lombada desta capa tem %s mm, não %s mm. Confira a lombada ou o "
                        + "tamanho do livro.").formatted(mm(spine), mm(declared.spineMm())));
            }
        } else if (Math.abs((width - tw) / 2 - edge) > TOLERANCE_MM) {
            throw mismatch("O PDF tem %s × %s mm, que não é uma capa de %s com a mesma sobra dos quatro lados."
                    .formatted(mm(width), mm(height), label(trim)));
        }
        Double bleed = declared.bleedMm();
        Double marks = null;
        if (bleed != null) {
            if (bleed > edge + TOLERANCE_MM / 2) {
                throw mismatch("Em volta da capa só há %s mm, menos que a sangria de %s mm. Confira a sangria."
                        .formatted(mm(edge), mm(bleed)));
            }
            marks = round(Math.max(0, edge - bleed));
        }
        return new Geometry(trim, spine, bleed, marks, width - edge - tw, edge, tw, th);
    }

    /** O PDF diz onde é o corte (TrimBox) e, às vezes, a sangria (BleedBox): vale o que ele diz. */
    private static Geometry fromBoxes(PDPage page, String kind, double width, double height, Declared declared) {
        if (!page.getCOSObject().containsKey(COSName.TRIM_BOX)) {
            return null;
        }
        PDRectangle trimBox = page.getTrimBox();
        double left = (trimBox.getLowerLeftX() - page.getMediaBox().getLowerLeftX()) * MM_PER_POINT;
        double top = height - (trimBox.getUpperRightY() - page.getMediaBox().getLowerLeftY()) * MM_PER_POINT;
        double trimW = trimBox.getWidth() * MM_PER_POINT;
        double trimH = trimBox.getHeight() * MM_PER_POINT;
        if (trimW > width - 0.5 && trimH > height - 0.5) {
            return null;
        }
        boolean wrap = "wrap".equals(kind);
        String trim = declared.trim() != null ? declared.trim() : byHeight(trimH, wrap ? null : trimW);
        double tw = trim != null ? BookLayout.trimWidthMm(trim) : wrap ? trimH * 2 / 3 : trimW;
        Double bleed = declared.bleedMm();
        if (page.getCOSObject().containsKey(COSName.BLEED_BOX)) {
            bleed = round((trimBox.getLowerLeftX() - page.getBleedBox().getLowerLeftX()) * MM_PER_POINT);
        }
        Double marks = bleed == null ? null : round(Math.max(0, left - bleed));
        Double spine = wrap ? round(Math.max(0, trimW - 2 * tw)) : null;
        return new Geometry(trim, spine, bleed, marks, left + trimW - tw, top, tw, trimH);
    }

    /** Capa aberta: o tamanho da diagramação, se a sobra em volta for de capa de verdade (de 0 a 25 mm). */
    private static String fitting(String trim, double width, double height, boolean wrap) {
        double edge = (height - BookLayout.trimHeightMm(trim)) / 2;
        boolean fits = edge >= -TOLERANCE_MM / 2 && edge <= MAX_EDGE_MM
                && (!wrap || width - 2 * edge - 2 * BookLayout.trimWidthMm(trim) >= 0);
        return fits ? trim : null;
    }

    /** Só a frente: o tamanho que deixa a mesma sobra em cima e do lado (a diagramação primeiro, se servir). */
    private static String symmetricTrim(double width, double height, String preferred) {
        List<String> order = new ArrayList<>(BookLayout.TRIMS);
        order.remove(preferred);
        order.addFirst(preferred);
        for (String trim : order) {
            double edgeY = (height - BookLayout.trimHeightMm(trim)) / 2;
            double edgeX = (width - BookLayout.trimWidthMm(trim)) / 2;
            if (edgeY >= -TOLERANCE_MM / 2 && edgeY <= MAX_EDGE_MM && Math.abs(edgeX - edgeY) <= TOLERANCE_MM) {
                return trim;
            }
        }
        return null;
    }

    /** O tamanho com essa altura (e essa largura, na frente sozinha), ou null. */
    private static String byHeight(double heightMm, Double widthMm) {
        return BookLayout.TRIMS.stream()
                .filter(t -> Math.abs(BookLayout.trimHeightMm(t) - heightMm) <= TOLERANCE_MM)
                .filter(t -> widthMm == null || Math.abs(BookLayout.trimWidthMm(t) - widthMm) <= TOLERANCE_MM)
                .findFirst().orElse(null);
    }

    private static void validate(Declared declared) {
        if (declared.trim() != null && !BookLayout.TRIMS.contains(declared.trim())) {
            throw ApiException.unprocessable("cover_pdf_spec_invalid", "Tamanho de livro desconhecido.");
        }
        if (declared.spineMm() != null && (declared.spineMm() < 0 || declared.spineMm() > 100)) {
            throw ApiException.unprocessable("cover_pdf_spec_invalid", "A lombada vai de 0 a 100 mm.");
        }
        if (declared.bleedMm() != null && (declared.bleedMm() < 0 || declared.bleedMm() > 10)) {
            throw ApiException.unprocessable("cover_pdf_spec_invalid", "A sangria vai de 0 a 10 mm.");
        }
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private static String label(String trim) {
        return "6x9".equals(trim) ? "6×9 pol." : "a5".equals(trim) ? "A5" : trim.replace('x', '×') + " cm";
    }

    private static String mm(double value) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%.1f", value).replace(",0", "");
    }

    private static double round(double mm) {
        return Math.round(mm * 10) / 10.0;
    }

    private static ApiException mismatch(String message) {
        return ApiException.unprocessable("cover_pdf_spec_mismatch", message);
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("cover_pdf_invalid", message);
    }
}
