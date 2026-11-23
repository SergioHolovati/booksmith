package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfBookReaderTest {

    private static final float MM = 72f / 25.4f;

    /** Um PDF com páginas coloridas nos tamanhos dados (largura, altura em mm). */
    private static File pdf(Path dir, float[][] sizes) throws Exception {
        File file = dir.resolve("livro.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            for (float[] size : sizes) {
                PDPage page = new PDPage(new PDRectangle(size[0] * MM, size[1] * MM));
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.setNonStrokingColor(new Color(240, 180, 40));
                    content.addRect(0, 0, size[0] * MM, size[1] * MM);
                    content.fill();
                }
            }
            document.save(file);
        }
        return file;
    }

    @Test
    void singlePagesAndSpreadsBecomeImagesAt300Dpi(@TempDir Path dir) throws Exception {
        // Capa (17 × 24) e uma dupla do livro aberto (34 × 24), como o PDF de um livro ilustrado
        List<PdfBookReader.RenderedPage> pages = new ArrayList<>();
        var size = PdfBookReader.read(pdf(dir, new float[][] {{170, 240}, {340, 240}}), pages::add);

        assertThat(size.widthMm()).isEqualTo(170.0);
        assertThat(size.heightMm()).isEqualTo(240.0);
        assertThat(size.bleed()).isFalse();
        assertThat(pages).extracting(PdfBookReader.RenderedPage::spread).containsExactly(false, true);
        // 170 mm a 300 DPI = 2008 px; a dupla, o dobro
        assertThat(pages.get(0).widthPx()).isBetween(2000, 2012);
        assertThat(pages.get(1).widthPx()).isBetween(4010, 4024);
        var image = ImageIO.read(new ByteArrayInputStream(pages.get(0).jpeg()));
        assertThat(image.getWidth()).isEqualTo(pages.get(0).widthPx());
        assertThat(new Color(image.getRGB(10, 10)).getRed()).isGreaterThan(220);
        assertThat((long) pages.get(1).jpeg().length).isLessThanOrEqualTo(PdfBookReader.MAX_IMAGE_BYTES);
    }

    @Test
    void aPdfAllInSpreadsMeasuresTheBookByHalfThePage(@TempDir Path dir) throws Exception {
        List<PdfBookReader.RenderedPage> pages = new ArrayList<>();
        var size = PdfBookReader.read(pdf(dir, new float[][] {{340, 240}, {340, 240}}), pages::add);
        assertThat(size.widthMm()).isEqualTo(170.0);
        assertThat(pages).allMatch(PdfBookReader.RenderedPage::spread);
    }

    @Test
    void notAPdfIsRefused(@TempDir Path dir) throws Exception {
        File file = dir.resolve("falso.pdf").toFile();
        java.nio.file.Files.writeString(file.toPath(), "não é um PDF");
        assertThatThrownBy(() -> PdfBookReader.read(file, page -> { }))
                .isInstanceOf(ApiException.class).hasMessageContaining("PDF");
    }

    @Test
    void aGiantPageIsRefusedBeforeBecomingAnImage(@TempDir Path dir) throws Exception {
        // 2 × 2 m: em 300 DPI seriam 560 milhões de pixels (a API cairia sem memória)
        File giant = pdf(dir, new float[][] {{170, 240}, {2000, 2000}});
        List<PdfBookReader.RenderedPage> pages = new ArrayList<>();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PdfBookReader.read(giant, pages::add))
                .isInstanceOf(com.booksmith.common.error.ApiException.class)
                .hasMessageContaining("grande demais");
        org.assertj.core.api.Assertions.assertThat(pages).isEmpty();
    }
}
