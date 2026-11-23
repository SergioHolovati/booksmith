package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PrintCoverTemplateTest {

    private static final double MM_PER_POINT = 25.4 / 72;

    @Test
    void theTemplateHasTheWrapAndTheFrontAtTheExactSizeWithTheTrimMarkedInThePdf() throws Exception {
        var spec = PrintCover.spec(PrintCover.Profile.KDP_WHITE, new BookLayout("6x9", null, null, null, null, null),
                200, null, null);
        byte[] pdf = PrintCoverTemplate.pdf(spec, "A Conversão 🔥", "KDP (Amazon), papel branco");
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            var wrap = document.getPage(0);
            assertThat(wrap.getMediaBox().getWidth() * MM_PER_POINT).isCloseTo(spec.widthMm(), within(0.05));
            assertThat(wrap.getMediaBox().getHeight() * MM_PER_POINT).isCloseTo(spec.heightMm(), within(0.05));
            // O corte no TrimBox: a importação lê dali o tamanho do livro, a lombada e a sangria
            assertThat(wrap.getTrimBox().getLowerLeftX() * MM_PER_POINT).isCloseTo(3.2, within(0.05));
            assertThat(wrap.getTrimBox().getHeight() * MM_PER_POINT).isCloseTo(228.6, within(0.05));
            var front = document.getPage(1);
            assertThat(front.getMediaBox().getWidth() * MM_PER_POINT).isCloseTo(152.4 + 6.4, within(0.05));

            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("CONTRACAPA", "FRENTE", "Código de barras", "A Conversão ?",
                    "lombada 11,4 mm", "sangria 3,2 mm");
            // A lombada vem girada: o extrator lê uma letra por linha
            assertThat(text.replaceAll("\\s", "")).contains("LOMBADA11,4mm");
        }
    }

    @Test
    void withFlapsTheTrimIsNotWrittenInThePdf() throws Exception {
        var spec = PrintCover.spec(PrintCover.Profile.CUSTOM, new BookLayout("16x23", null, null, null, null, null),
                120, 10.0, 3.0, 80.0);
        try (var document = Loader.loadPDF(PrintCoverTemplate.pdf(spec, "Livro", "Outra gráfica"))) {
            var wrap = document.getPage(0);
            assertThat(wrap.getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.TRIM_BOX)).isFalse();
            assertThat(new PDFTextStripper().getText(document)).contains("ORELHA", "Orelhas: 80 mm cada");
        }
    }

    @Test
    void measuresAreWrittenTheBrazilianWay() {
        assertThat(PrintCoverTemplate.mm(148.0)).isEqualTo("148");
        assertThat(PrintCoverTemplate.mm(11.44)).isEqualTo("11,4");
        assertThat(PrintCoverTemplate.printable(new PDType1Font(Standard14Fonts.FontName.HELVETICA), "Ação — “sim” 🔥"))
                .isEqualTo("Ação — “sim” ?");
    }
}
