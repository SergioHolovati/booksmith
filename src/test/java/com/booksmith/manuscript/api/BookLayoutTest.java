package com.booksmith.manuscript.api;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookLayoutTest {

    @Test
    void squareAndLandscapeSizes() {
        assertThat(size("20x20")).containsExactly(200, 200);
        assertThat(size("21x15")).containsExactly(210, 150);
        assertThat(size("28x21")).containsExactly(280, 210);
        assertThat(size("8.5x8.5")).containsExactly(215.9, 215.9);
        assertThat(size("8.25x6")).containsExactly(209.6, 152.4);
        // Os de antes não mudam
        assertThat(size("a5")).containsExactly(148, 210);
        assertThat(size("6x9")).containsExactly(152.4, 228.6);
    }

    @Test
    void customSizeNeedsWidthAndHeightInRange() {
        var custom = new BookLayout("custom", null, null, null, null, null, 230.04, 180.0, null);
        assertThat(custom.widthMm()).isEqualTo(230.0);
        assertThat(custom.heightMm()).isEqualTo(180.0);

        assertThatThrownBy(() -> new BookLayout("custom", null, null, null, null, null, 230.0, null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("largura e a altura");
        assertThatThrownBy(() -> new BookLayout("custom", null, null, null, null, null, 99.0, 150.0, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new BookLayout("custom", null, null, null, null, null, 150.0, 331.0, null))
                .isInstanceOf(ApiException.class);
        // Fora do personalizado, as medidas soltas são ignoradas
        var a5 = new BookLayout("a5", null, null, null, null, null, 300.0, 300.0, null);
        assertThat(a5.customWidthMm()).isNull();
        assertThat(a5.widthMm()).isEqualTo(148);
    }

    @Test
    void bleedIsOffUnlessAsked() {
        assertThat(BookLayout.DEFAULT.hasBleed()).isFalse();
        assertThat(BookLayout.DEFAULT.bleed()).isFalse();
        assertThat(new BookLayout("20x20", null, null, null, null, null, null, null, true).hasBleed()).isTrue();
    }

    @Test
    void oldSavedLayoutStillReads() {
        var mapper = JsonMapper.builder().build();
        var old = mapper.readValue("{\"trim\":\"16x23\",\"fontSize\":10.5}", BookLayout.class);
        assertThat(old.trim()).isEqualTo("16x23");
        assertThat(old.hasBleed()).isFalse();
        assertThat(old.customWidthMm()).isNull();
        // E volta a ser lida igual depois de salva
        assertThat(mapper.readValue(mapper.writeValueAsString(old), BookLayout.class)).isEqualTo(old);
    }

    private static double[] size(String trim) {
        var layout = new BookLayout(trim, null, null, null, null, null);
        return new double[] {layout.widthMm(), layout.heightMm()};
    }

    @Test
    void fontIsLiterataByDefaultAndMustBeFromTheCatalog() {
        assertThat(BookLayout.DEFAULT.font()).isEqualTo("literata");
        assertThat(new BookLayout(null, null, null, null, null, null, null, null, null, null, null, null, "andika")
                .bookFont()).isEqualTo(BookFont.ANDIKA);
        assertThatThrownBy(() -> new BookLayout(null, null, null, null, null, null, null, null, null, null, null, null,
                "Arial; color: red"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("layout_invalid"));
    }

    @Test
    void pageFontIsOptionalAndFromTheCatalog() {
        var inherits = new PageDesign(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, " ");
        assertThat(inherits.font()).isNull();
        assertThat(inherits.fontOr(BookFont.NUNITO)).isEqualTo(BookFont.NUNITO);
        var own = new PageDesign(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, "fredoka");
        assertThat(own.fontOr(BookFont.NUNITO)).isEqualTo(BookFont.FREDOKA);
        assertThatThrownBy(() -> new PageDesign(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, "'Comic Sans'"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("page_invalid"));
    }

    @Test
    void everyFontInTheCatalogHasItsFiles() {
        for (BookFont font : BookFont.values()) {
            assertThat(BookFont.class.getResource("/export/fonts/" + font.fileName("Regular"))).as(font.id()).isNotNull();
            if (font.hasBold()) {
                assertThat(BookFont.class.getResource("/export/fonts/" + font.fileName("Bold"))).as(font.id()).isNotNull();
            }
            if (font.hasItalic()) {
                assertThat(BookFont.class.getResource("/export/fonts/" + font.fileName("BoldItalic"))).as(font.id())
                        .isNotNull();
            }
        }
    }
}
