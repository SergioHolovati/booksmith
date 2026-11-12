package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.media.api.ImageStorage.ImageData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PrintCheckTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final double MM_PER_POINT = 25.4 / 72;

    private static final String SMALL = "0196f000-0000-7000-8000-000000000001";
    private static final String WIDE = "0196f000-0000-7000-8000-000000000002";
    private static final String TALL = "0196f000-0000-7000-8000-000000000003";
    private static final String WEBP = "0196f000-0000-7000-8000-000000000004";

    private static ExportBook book() throws IOException {
        Map<String, ImageData> images = new LinkedHashMap<>();
        images.put(SMALL, new ImageData(png(400, 300), "image/png", "png"));
        images.put(WIDE, new ImageData(png(3000, 1000), "image/png", "png"));
        images.put(TALL, new ImageData(png(1000, 3000), "image/png", "png"));
        images.put(WEBP, new ImageData(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, "image/webp", "webp"));
        String doc = """
                {"type":"doc","content":[
                  {"type":"paragraph","attrs":{"id":"b_p1"},"content":[{"type":"text","text":"Ela olhou o mapa."}]},
                  {"type":"illustration","attrs":{"id":"b_i1","imageId":"%s","caption":"O mapa de Vel"}},
                  {"type":"illustration","attrs":{"id":"b_i2","imageId":"%s","caption":null}},
                  {"type":"illustration","attrs":{"id":"b_i3","imageId":"%s","caption":"A torre"}},
                  {"type":"illustration","attrs":{"id":"b_i4","imageId":"%s","caption":"O selo"}},
                  {"type":"illustration","attrs":{"id":"b_i5","imageId":"%s","caption":"O mapa de novo"}}
                ]}""".formatted(SMALL, WIDE, TALL, WEBP, SMALL);
        return new ExportBook(UUID.randomUUID(), "A Conversão", "Lia Moreno", "pt-BR", null, null, null,
                Instant.parse("2026-11-12T12:00:00Z"), List.of(new ExportBook.Chapter("O mapa", JSON.readTree(doc))),
                images);
    }

    @Test
    void sizeOnThePageGivesTheDpi() throws IOException {
        PrintCheck.Result result = PrintCheck.check(book());

        // A5 com margens normais: 148 − 2 × 17 = 114 mm de texto
        assertThat(result.textWidthMm()).isEqualTo(114.0);
        assertThat(result.illustrations()).extracting(PrintCheck.Illustration::imageId)
                .containsExactly(SMALL, WIDE, TALL, WEBP);
        PrintCheck.Illustration small = result.illustrations().get(0);
        // 400 px a 96 por polegada = 105,8 mm: cabe, sai nesse tamanho, 96 DPI
        assertThat(small).extracting(PrintCheck.Illustration::widthMm, PrintCheck.Illustration::dpi,
                PrintCheck.Illustration::ok, PrintCheck.Illustration::caption).containsExactly(105.8, 96, false, "O mapa de Vel");
        assertThat(small.minWidthPx()).isEqualTo(1250);
        // Larga: reduzida à largura do texto; alta: reduzida a 140 mm de altura
        assertThat(result.illustrations().get(1)).extracting(PrintCheck.Illustration::widthMm, PrintCheck.Illustration::dpi,
                PrintCheck.Illustration::ok).containsExactly(114.0, 668, true);
        assertThat(result.illustrations().get(2)).extracting(PrintCheck.Illustration::heightMm,
                PrintCheck.Illustration::widthMm, PrintCheck.Illustration::dpi).containsExactly(140.0, 46.7, 544);
        // WebP não entra no PDF
        assertThat(result.illustrations().get(3)).extracting(PrintCheck.Illustration::printable,
                PrintCheck.Illustration::ok).containsExactly(false, false);
    }

    @Test
    void theCalculationMatchesWhereThePdfDrawsTheImages() throws IOException {
        ExportBook book = book();
        List<double[]> drawn = drawnImagesMm(PdfWriter.write(book));
        List<PrintCheck.Illustration> expected = PrintCheck.check(book).illustrations().stream()
                .filter(PrintCheck.Illustration::printable).toList();

        // A pequena aparece duas vezes no texto (mesmo capítulo: conferida uma vez)
        assertThat(drawn).hasSize(4);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(drawn.get(i)[0]).isCloseTo(expected.get(i).widthMm(), within(0.5));
            assertThat(drawn.get(i)[1]).isCloseTo(expected.get(i).heightMm(), within(0.5));
        }
    }

    @Test
    void wideMarginsOnALargerPageChangeTheRoom() throws IOException {
        ExportBook book = book().withLayout(new BookLayout("16x23", null, null, "wide", null, null));
        // 160 − 2 × 21 = 118 mm
        assertThat(PrintCheck.check(book).textWidthMm()).isEqualTo(118.0);
        assertThat(PrintCheck.check(book).illustrations().get(1).dpi()).isEqualTo(645);
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** Largura e altura, em mm, de cada imagem desenhada no PDF, na ordem. */
    private static List<double[]> drawnImagesMm(byte[] pdf) throws IOException {
        List<double[]> sizes = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (PDPage page : document.getPages()) {
                new PDFGraphicsStreamEngine(page) {
                    @Override
                    public void drawImage(PDImage image) {
                        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                        sizes.add(new double[] {ctm.getScalingFactorX() * MM_PER_POINT, ctm.getScalingFactorY() * MM_PER_POINT});
                    }

                    @Override
                    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
                    }

                    @Override
                    public void clip(int windingRule) {
                    }

                    @Override
                    public void moveTo(float x, float y) {
                    }

                    @Override
                    public void lineTo(float x, float y) {
                    }

                    @Override
                    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
                    }

                    @Override
                    public Point2D getCurrentPoint() {
                        return new Point2D.Float();
                    }

                    @Override
                    public void closePath() {
                    }

                    @Override
                    public void endPath() {
                    }

                    @Override
                    public void strokePath() {
                    }

                    @Override
                    public void fillPath(int windingRule) {
                    }

                    @Override
                    public void fillAndStrokePath(int windingRule) {
                    }

                    @Override
                    public void shadingFill(COSName shadingName) {
                    }

                    void run() throws IOException {
                        processPage(page);
                    }
                }.run();
            }
        }
        return sizes;
    }
}
