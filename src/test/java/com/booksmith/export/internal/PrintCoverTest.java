package com.booksmith.export.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.BookLayout;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PrintCoverTest {

    private static final BookLayout SIX_BY_NINE = new BookLayout("6x9", null, null, null, null, null);

    @Test
    void kdpSpineIsPagesTimesPaperThickness() {
        // Exemplo da ajuda do KDP: 6×9, 200 páginas, papel branco → lombada de 0,4504" (11,44 mm)
        var spec = PrintCover.spec(PrintCover.Profile.KDP_WHITE, SIX_BY_NINE, 200, null, null);
        assertThat(spec.spineMm()).isEqualTo(11.4);
        assertThat(spec.bleedMm()).isEqualTo(3.2);
        // 3,2 + 152,4 + 11,44 + 152,4 + 3,2
        assertThat(spec.widthMm()).isCloseTo(322.6, within(0.1));
        assertThat(spec.heightMm()).isCloseTo(235.0, within(0.1));
        assertThat(spec.spineText()).isTrue();
        assertThat(spec.warnings()).isEmpty();

        var cream = PrintCover.spec(PrintCover.Profile.KDP_CREAM, SIX_BY_NINE, 200, null, null);
        assertThat(cream.spineMm()).isEqualTo(12.7);
    }

    @Test
    void kdpWarnsAboutSizeAndPageCount() {
        var spec = PrintCover.spec(PrintCover.Profile.KDP_WHITE, new BookLayout("16x23", null, null, null, null, null),
                20, null, null);
        assertThat(spec.spineText()).isFalse();
        assertThat(spec.warnings()).hasSize(2);
        assertThat(spec.warnings().getFirst()).contains("A5 ou 6×9");
    }

    @Test
    void customPrinterGivesSpineAndBleed() {
        var spec = PrintCover.spec(PrintCover.Profile.CUSTOM, BookLayout.DEFAULT, 150, 9.5, null);
        assertThat(spec.spineMm()).isEqualTo(9.5);
        assertThat(spec.bleedMm()).isEqualTo(3.0);
        assertThat(spec.widthMm()).isCloseTo(3 + 148 + 9.5 + 148 + 3, within(0.01));
        assertThat(spec.spineText()).isTrue();
        assertThatThrownBy(() -> PrintCover.spec(PrintCover.Profile.CUSTOM, BookLayout.DEFAULT, 150, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("print_spine_invalid"));
        assertThatThrownBy(() -> PrintCover.Profile.of("gráfica"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("print_profile_invalid"));
        assertThat(PrintCover.Profile.of("kdp-cream")).isEqualTo(PrintCover.Profile.KDP_CREAM);
    }

    @Test
    void pdfHasTheExactCoverSize() throws Exception {
        var spec = PrintCover.spec(PrintCover.Profile.KDP_WHITE, SIX_BY_NINE, 200, null, null);
        int width = 1000;
        int height = (int) Math.round(width * spec.heightMm() / spec.widthMm());
        byte[] pdf = PrintCover.pdf(spec, png(width, height));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            var box = document.getPage(0).getMediaBox();
            assertThat(box.getWidth()).isCloseTo((float) (spec.widthMm() / 25.4 * 72), within(0.5f));
            assertThat(box.getHeight()).isCloseTo((float) (spec.heightMm() / 25.4 * 72), within(0.5f));
        }
        // Imagem na proporção errada (a frente sozinha) é recusada
        assertThatThrownBy(() -> PrintCover.pdf(spec, png(1000, 1600)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("print_cover_image_invalid"));
        assertThatThrownBy(() -> PrintCover.pdf(spec, "data:image/webp;base64,AAAA"))
                .isInstanceOf(ApiException.class);
    }

    private static String png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
