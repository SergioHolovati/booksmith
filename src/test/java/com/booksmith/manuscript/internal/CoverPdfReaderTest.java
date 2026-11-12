package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.BookLayout;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CoverPdfReaderTest {

    @Test
    void portraitPageIsTheFront() throws Exception {
        var result = CoverPdfReader.read(pdf(mm(154.4), mm(216.4), null), BookLayout.DEFAULT);
        assertThat(result.kind()).isEqualTo("front");
        assertThat(result.widthMm()).isCloseTo(154.4, within(0.1));
        assertThat(result.heightMm()).isCloseTo(216.4, within(0.1));
        assertThat(result.frontImage()).startsWith("data:image/jpeg;base64,");
    }

    @Test
    void landscapePageIsTheWholeCover() throws Exception {
        // A5 aberto: 3 + 148 + 10 (lombada) + 148 + 3 por 3 + 210 + 3
        var result = CoverPdfReader.read(pdf(mm(312), mm(216), null), BookLayout.DEFAULT);
        assertThat(result.kind()).isEqualTo("wrap");
        assertThat(result.widthMm()).isCloseTo(312, within(0.1));
        assertThat(result.pages()).isEqualTo(1);
    }

    @Test
    void protectedOrBrokenPdfIsRejected() throws Exception {
        assertThatThrownBy(() -> CoverPdfReader.read(pdf(mm(148), mm(210), "segredo"), BookLayout.DEFAULT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("cover_pdf_protected"));
        assertThatThrownBy(() -> CoverPdfReader.read("%PDF-1.7 quebrado".getBytes(), BookLayout.DEFAULT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("cover_pdf_invalid"));
    }

    /**
     * A capa de "A conversão": 16×23, lombada 48, sangria 5 e 12,1 mm de marcas de corte em volta (402,2 × 264,2
     * mm). A margem das marcas é branca; a capa com a sangria, laranja; a frente no corte, azul.
     */
    static byte[] coverWithMarks() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(mm(402.2), mm(264.2)));
            document.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                content.setNonStrokingColor(new java.awt.Color(240, 130, 30));
                content.addRect(mm(12.1), mm(12.1), mm(378), mm(240));
                content.fill();
                content.setNonStrokingColor(java.awt.Color.BLUE);
                // a frente no corte: x = 12,1 + 5 + 160 + 48; y = 12,1 + 5
                content.addRect(mm(225.1), mm(17.1), mm(160), mm(230));
                content.fill();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void withTheBookSizeTheFrontIsCutAtTheTrimWithoutMarks() throws Exception {
        var result = CoverPdfReader.read(coverWithMarks(), BookLayout.DEFAULT,
                new CoverPdfReader.Declared("16x23", 48.0, 5.0));

        assertThat(result.trim()).isEqualTo("16x23");
        assertThat(result.spineMm()).isCloseTo(48, within(0.2));
        assertThat(result.bleedMm()).isEqualTo(5.0);
        assertThat(result.marksMm()).isCloseTo(12.1, within(0.2));
        // a frente inteira é azul: nada da margem branca, da sangria laranja ou da lombada
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder()
                .decode(result.frontImage().substring(result.frontImage().indexOf(',') + 1))));
        assertThat((double) image.getWidth() / image.getHeight()).isCloseTo(160.0 / 230, within(0.01));
        for (int[] at : new int[][] {{3, 3}, {image.getWidth() - 4, 3}, {3, image.getHeight() - 4},
                {image.getWidth() - 4, image.getHeight() - 4}, {image.getWidth() / 2, image.getHeight() / 2}}) {
            var color = new java.awt.Color(image.getRGB(at[0], at[1]));
            assertThat(color.getBlue()).as("pixel %s,%s", at[0], at[1]).isGreaterThan(200);
            assertThat(color.getRed()).as("pixel %s,%s", at[0], at[1]).isLessThan(60);
        }
    }

    @Test
    void theSizeAloneIsEnoughAndTheSpecIsChecked() throws Exception {
        var sizeOnly = CoverPdfReader.read(coverWithMarks(), BookLayout.DEFAULT,
                new CoverPdfReader.Declared("16x23", null, null));
        assertThat(sizeOnly.spineMm()).isCloseTo(48, within(0.2));
        assertThat(sizeOnly.marksMm()).isNull();

        assertThatThrownBy(() -> CoverPdfReader.read(coverWithMarks(), BookLayout.DEFAULT,
                new CoverPdfReader.Declared("16x23", 30.0, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("cover_pdf_spec_mismatch");
                    assertThat(e.getMessage()).contains("48 mm");
                });
        // Nada informado e o livro diagramado em 14×21: não dá para adivinhar (16×23 e 6×9 caberiam)
        var unknown = CoverPdfReader.read(coverWithMarks(), new BookLayout("14x21", null, null, null, null, null));
        assertThat(unknown.trim()).isNull();
    }

    @Test
    void aBookSizedLayoutIsUsedWhenNothingWasDeclared() throws Exception {
        var result = CoverPdfReader.read(coverWithMarks(), new BookLayout("16x23", null, null, null, null, null));
        assertThat(result.trim()).isEqualTo("16x23");
        assertThat(result.spineMm()).isCloseTo(48, within(0.2));
    }

    private static float mm(double mm) {
        return (float) (mm / 25.4 * 72);
    }

    static byte[] pdf(float width, float height, String password) throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(width, height)));
            if (password != null) {
                var policy = new StandardProtectionPolicy("dono", password, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
