package com.booksmith.export.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.BookLayout;
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
     */
    public record Spec(String profile, int pages, double trimWidthMm, double trimHeightMm, double spineMm,
                       double bleedMm, double widthMm, double heightMm, boolean spineText, List<String> warnings) {
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
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
