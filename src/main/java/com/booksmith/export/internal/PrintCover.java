package com.booksmith.export.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.manuscript.api.CoverPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Capa aberta para gráfica (§4.7 do produto, §13.3): contracapa + lombada + frente, com sangria em volta.
 * Largura = sangria + contracapa + lombada + frente + sangria; altura = sangria + altura do livro + sangria.
 * A imagem é desenhada no aparelho (300 DPI) e aqui vira um PDF no tamanho exato.
 */
public final class PrintCover {

    /** Milímetros por polegada; o PDF mede em pontos (72 por polegada). */
    private static final double MM_PER_INCH = 25.4;

    /** Tamanhos de livro que o KDP aceita entre os da diagramação (A5 e 6×9). */
    private static final Set<String> KDP_TRIMS = Set.of("a5", "6x9");

    /** No KDP, o livro impresso tem de 24 páginas para cima e só ganha texto na lombada com mais de 79. */
    static final int KDP_MIN_PAGES = 24;
    static final int KDP_SPINE_TEXT_PAGES = 80;

    /** Em outra gráfica, texto na lombada só a partir de 6 mm: menos que isso não cabe uma linha legível. */
    static final double MIN_SPINE_TEXT_MM = 6;

    /** Diferença aceita entre a proporção da imagem enviada e a da capa (arredondamento de pixels). */
    private static final double RATIO_TOLERANCE = 0.01;

    private PrintCover() {
    }

    /**
     * Onde a capa vai ser impressa. Nos perfis do KDP, a lombada é número de páginas × espessura do papel e a
     * sangria é de 3,2 mm (0,125"), como na ajuda do KDP; em {@link #CUSTOM}, a gráfica informa as duas medidas.
     */
    public enum Profile {
        KDP_WHITE(0.0572), KDP_CREAM(0.0635), KDP_COLOR_PREMIUM(0.0596), CUSTOM(0);

        /** Espessura de uma página, em mm (0 = a gráfica informa a lombada). */
        final double pageMm;

        Profile(double pageMm) {
            this.pageMm = pageMm;
        }

        boolean kdp() {
            return this != CUSTOM;
        }

        /** "kdp-cream" → {@link #KDP_CREAM}. */
        public static Profile of(String value) {
            for (Profile profile : values()) {
                if (profile.name().replace('_', '-').equalsIgnoreCase(value)) {
                    return profile;
                }
            }
            throw ApiException.unprocessable("print_profile_invalid", "Escolha onde o livro vai ser impresso.");
        }
    }

    /**
     * As medidas da capa aberta, em mm.
     *
     * @param pages      páginas do miolo (o PDF diagramado, sem a página da capa)
     * @param spineText  cabe texto na lombada
     * @param warnings   o que impede ou atrapalha a impressão nesse destino (vazio = tudo certo)
     * @param imported   a capa importada em PDF ({@code front} ou {@code wrap}), ou null
     * @param importedFits na capa aberta importada: as medidas batem com as pedidas (até 1 mm de diferença)
     */
    public record Spec(String profile, int pages, double trimWidthMm, double trimHeightMm, double spineMm,
                       double bleedMm, double widthMm, double heightMm, boolean spineText, List<String> warnings,
                       String imported, Double importedWidthMm, Double importedHeightMm, Boolean importedFits) {

        Spec(String profile, int pages, double trimWidthMm, double trimHeightMm, double spineMm, double bleedMm,
             double widthMm, double heightMm, boolean spineText, List<String> warnings) {
            this(profile, pages, trimWidthMm, trimHeightMm, spineMm, bleedMm, widthMm, heightMm, spineText, warnings,
                    null, null, null, null);
        }

        /**
         * As mesmas medidas, comparadas com a capa importada. Na capa aberta, diz quanto a lombada (a diferença de
         * largura) e a altura estão fora.
         */
        Spec withImported(CoverPdf cover) {
            if (cover == null) {
                return this;
            }
            Boolean fits = null;
            List<String> notes = new ArrayList<>(warnings);
            if (cover.wrap()) {
                double dw = cover.widthMm() - widthMm;
                double dh = cover.heightMm() - heightMm;
                fits = Math.abs(dw) <= FIT_TOLERANCE_MM && Math.abs(dh) <= FIT_TOLERANCE_MM;
                if (Math.abs(dw) > FIT_TOLERANCE_MM) {
                    notes.add("A sua capa tem %s mm de largura; para este livro são %s mm. Refaça com a lombada de %s mm (%s mm %s)."
                            .formatted(mm(cover.widthMm()), mm(widthMm), mm(spineMm), mm(Math.abs(dw)),
                                    dw > 0 ? "a menos" : "a mais"));
                }
                if (Math.abs(dh) > FIT_TOLERANCE_MM) {
                    notes.add("A sua capa tem %s mm de altura; com a sangria, são %s mm.".formatted(mm(cover.heightMm()),
                            mm(heightMm)));
                }
            }
            return new Spec(profile, pages, trimWidthMm, trimHeightMm, spineMm, bleedMm, widthMm, heightMm, spineText,
                    List.copyOf(notes), cover.kind(), cover.widthMm(), cover.heightMm(), fits);
        }
    }

    /** Diferença aceita entre a capa aberta importada e a medida pedida. */
    static final double FIT_TOLERANCE_MM = 1;

    /** 12.45 → "12,5" (mensagens para o autor). */
    private static String mm(double value) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%.1f", value).replace(",0", "");
    }

    /**
     * @param customSpineMm lombada informada pela gráfica (só no perfil {@code CUSTOM})
     * @param customBleedMm sangria informada pela gráfica (só no perfil {@code CUSTOM}; null = 3 mm)
     */
    static Spec spec(Profile profile, BookLayout layout, int pages, Double customSpineMm, Double customBleedMm) {
        double spine;
        double bleed;
        List<String> warnings = new ArrayList<>();
        if (profile.kdp()) {
            spine = pages * profile.pageMm;
            bleed = 3.2;
            if (!KDP_TRIMS.contains(layout.trim())) {
                warnings.add("O KDP não aceita o tamanho " + layout.trim() + ". Na diagramação, escolha A5 ou 6×9.");
            }
            if (pages < KDP_MIN_PAGES) {
                warnings.add("O KDP imprime livros a partir de " + KDP_MIN_PAGES + " páginas; este tem " + pages + ".");
            }
        } else {
            if (customSpineMm == null || customSpineMm <= 0 || customSpineMm > 100) {
                throw ApiException.unprocessable("print_spine_invalid",
                        "Informe a lombada que a gráfica calculou, em milímetros (até 100).");
            }
            if (customBleedMm != null && (customBleedMm < 0 || customBleedMm > 10)) {
                throw ApiException.unprocessable("print_bleed_invalid", "A sangria vai de 0 a 10 mm.");
            }
            spine = customSpineMm;
            bleed = customBleedMm == null ? 3 : customBleedMm;
        }
        boolean spineText = profile.kdp() ? pages >= KDP_SPINE_TEXT_PAGES : spine >= MIN_SPINE_TEXT_MM;
        double width = 2 * bleed + 2 * layout.widthMm() + spine;
        double height = 2 * bleed + layout.heightMm();
        return new Spec(profile.name().toLowerCase(Locale.ROOT).replace('_', '-'), pages, layout.widthMm(),
                layout.heightMm(), round(spine), bleed, round(width), round(height), spineText, List.copyOf(warnings));
    }

    /**
     * PDF de uma página no tamanho da capa aberta, com a imagem ocupando tudo.
     *
     * @throws ApiException 422 {@code print_cover_image_invalid} se não for JPEG/PNG ou se a proporção não bater
     */
    static byte[] pdf(Spec spec, String imageDataUrl) {
        return pdf(spec, imageDataUrl, null);
    }

    /**
     * Idem, com a frente vinda do PDF do autor por cima, como vetor (sem perder qualidade). A frente cobre o painel
     * da frente com a sangria; se o PDF não tem sangria, é ampliado um pouco, a partir do centro do livro.
     */
    static byte[] pdf(Spec spec, String imageDataUrl, byte[] frontPdf) {
        byte[] bytes = decode(imageDataUrl);
        try (PDDocument document = new PDDocument()) {
            float width = points(spec.widthMm());
            float height = points(spec.heightMm());
            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            PDImageXObject image = image(document, bytes);
            double ratio = (double) image.getWidth() / image.getHeight();
            if (Math.abs(ratio - spec.widthMm() / spec.heightMm()) > RATIO_TOLERANCE * ratio) {
                throw invalidImage("A imagem não tem a proporção da capa. Gere de novo pela tela da capa.");
            }
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 0, 0, width, height);
                if (frontPdf != null) {
                    drawFront(document, content, spec, frontPdf);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void drawFront(PDDocument document, PDPageContentStream content, Spec spec, byte[] frontPdf)
            throws IOException {
        try (PDDocument source = Loader.loadPDF(frontPdf)) {
            PDFormXObject front = new LayerUtility(document).importPageAsForm(source, 0);
            PDRectangle box = front.getBBox();
            float bleed = points(spec.bleedMm());
            float trimW = points(spec.trimWidthMm());
            float trimH = points(spec.trimHeightMm());
            float panelX = points(spec.bleedMm() + spec.trimWidthMm() + spec.spineMm());
            // Cobre o livro mais a sangria em volta, centrado no centro do livro (não no do painel)
            float scale = Math.max((trimW + 2 * bleed) / box.getWidth(), (trimH + 2 * bleed) / box.getHeight());
            float centerX = panelX + trimW / 2;
            float centerY = bleed + trimH / 2;
            content.saveGraphicsState();
            // Não passa para a lombada: corta no começo do painel da frente
            content.addRect(panelX, 0, trimW + bleed, trimH + 2 * bleed);
            content.clip();
            content.transform(new Matrix(scale, 0, 0, scale,
                    centerX - (box.getLowerLeftX() + box.getWidth() / 2) * scale,
                    centerY - (box.getLowerLeftY() + box.getHeight() / 2) * scale));
            content.drawForm(front);
            content.restoreGraphicsState();
        }
    }

    private static PDImageXObject image(PDDocument document, byte[] bytes) {
        try {
            return PDImageXObject.createFromByteArray(document, bytes, "capa");
        } catch (IOException | IllegalArgumentException e) {
            throw invalidImage("Não conseguimos ler a imagem da capa.").causedBy(e);
        }
    }

    private static byte[] decode(String dataUrl) {
        if (dataUrl == null || !(dataUrl.startsWith("data:image/jpeg;base64,") || dataUrl.startsWith("data:image/png;base64,"))) {
            throw invalidImage("A capa precisa ser uma imagem JPEG ou PNG.");
        }
        try {
            return Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        } catch (IllegalArgumentException e) {
            throw invalidImage("A imagem da capa veio corrompida.").causedBy(e);
        }
    }

    private static ApiException invalidImage(String message) {
        return ApiException.unprocessable("print_cover_image_invalid", message);
    }

    private static float points(double mm) {
        return (float) (mm / MM_PER_INCH * 72);
    }

    private static double round(double mm) {
        return Math.round(mm * 10) / 10.0;
    }
}
