package com.booksmith.export.internal;

import com.adobe.epubcheck.api.EpubCheck;
import com.booksmith.media.api.ImageStorage.ImageData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportWritersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** PNG 1×1, só para ter uma capa de verdade dentro do ePub. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final String CHAPTER_ONE = """
            {"type":"doc","attrs":{"schemaVersion":1},"content":[
              {"type":"paragraph","attrs":{"id":"p1","indent":true},"content":[
                {"type":"text","text":"Lia entrou no café & sentou "},
                {"type":"text","text":"devagar","marks":[{"type":"italic"},{"type":"bold"}]},
                {"type":"text","text":" — sem saber <nada>."}]},
              {"type":"paragraph","attrs":{"id":"p2"}},
              {"type":"sceneBreak","attrs":{"id":"s1"}},
              {"type":"heading","attrs":{"id":"h1","level":1},"content":[{"type":"text","text":"Mais tarde"}]},
              {"type":"blockquote","attrs":{"id":"q1"},"content":[
                {"type":"paragraph","attrs":{"id":"p3"},"content":[
                  {"type":"text","text":"Querida,"},{"type":"hardBreak"},{"type":"text","text":"volte logo."}]}]},
              {"type":"orderedList","attrs":{"id":"l1","start":3},"content":[
                {"type":"listItem","attrs":{"id":"i1"},"content":[
                  {"type":"paragraph","attrs":{"id":"p4"},"content":[{"type":"text","text":"pão\\u0007"}]}]}]}
            ]}""";

    @Test
    void rendersTheManuscriptAsBookXhtml() {
        String html = BookHtml.body(JSON.readTree(CHAPTER_ONE));

        assertThat(html).contains("<p class=\"indent\">Lia entrou no café &amp; sentou "
                + "<strong><em>devagar</em></strong> — sem saber &lt;nada&gt;.</p>");
        assertThat(html).contains("<p class=\"blank\">&#160;</p>");
        assertThat(html).contains("<p class=\"scene-break\">* * *</p>");
        assertThat(html).contains("<h2>Mais tarde</h2>");
        assertThat(html).contains("<blockquote>\n<p>Querida,<br/>volte logo.</p>\n</blockquote>");
        // caractere de controle sai, para o XML continuar válido
        assertThat(html).contains("<ol start=\"3\">\n<li><p>pão</p>\n</li>");
    }

    @Test
    void mentionIsExportedAsPlainName() {
        var chapter = JSON.readTree("""
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_p1"},"content":[
                  {"type":"text","text":"Então "},
                  {"type":"mention","attrs":{"id":"0196f000-0000-7000-8000-00003f9a2c71","kind":"character","label":"Lia"}},
                  {"type":"text","text":" sorriu."}]}]}""");

        assertThat(BookHtml.body(chapter)).contains("Então Lia sorriu.").doesNotContain("mention");
    }

    @Test
    void authorNotesNeverReachTheExportedBook() {
        var chapter = JSON.readTree("""
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_p1"},"content":[
                  {"type":"text","marks":[{"type":"commentAnchor","attrs":{"threadId":"0196f000-0000-7000-8000-00003f9a2c71"}}],"text":"Era uma vez"}]}]}""");

        assertThat(BookHtml.body(chapter)).contains(">Era uma vez<").doesNotContain("threadId").doesNotContain("comment");
    }

    @Test
    void proofreadOffDoesNotChangeTheExportedText() {
        var chapter = JSON.readTree("""
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_p1"},"content":[
                  {"type":"text","text":"Ele disse: "},
                  {"type":"text","marks":[{"type":"proofreadOff"}],"text":"nóis vai"}]}]}""");

        assertThat(BookHtml.body(chapter)).contains("<p>Ele disse: nóis vai</p>");
    }

    @Test
    void untitledChapterGetsItsNumber() {
        assertThat(BookHtml.chapterTitle("  ", 2)).isEqualTo("Capítulo 2");
        assertThat(BookHtml.chapterTitle(" O Encontro ", 1)).isEqualTo("O Encontro");
    }

    @Test
    void filenameIsTheSlugOfTheTitle() {
        assertThat(ExportService.filename("A Conversão", ExportFormat.EPUB)).isEqualTo("a-conversao.epub");
        assertThat(ExportService.filename("!!!", ExportFormat.PDF)).isEqualTo("livro.pdf");
    }

    @Test
    void epubPassesEpubcheck(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(book(new ImageData(PNG, "image/png", "png"), "Harry Potter"));
        Path file = dir.resolve("livro.epub");
        Files.write(file, epub);

        StringWriter messages = new StringWriter();
        int result = new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate();

        assertThat(result).withFailMessage(messages::toString).isZero();
        List<String> entries = entries(epub);
        assertThat(entries.getFirst()).isEqualTo("mimetype");
        assertThat(entries).contains("EPUB/images/cover.png", "EPUB/fan-notice.xhtml", "EPUB/chapter-002.xhtml");
    }

    private static final String IMAGE_ID = "0196f000-0000-7000-8000-00003f9a2c71";

    private static ExportBook illustrated(String contentType) {
        String doc = """
                {"type":"doc","content":[
                  {"type":"paragraph","attrs":{"id":"b_p1"},"content":[{"type":"text","text":"Ela olhou o mapa."}]},
                  {"type":"illustration","attrs":{"id":"b_il","imageId":"%s","caption":"O mapa de Vel"}},
                  {"type":"illustration","attrs":{"id":"b_i2","imageId":"0196f000-0000-7000-8000-000000000000","caption":""}}
                ]}""".formatted(IMAGE_ID);
        return new ExportBook(UUID.randomUUID(), "A Conversão", "Sérgio Holovati", "pt-BR", null, null, null,
                Instant.parse("2026-11-10T12:00:00Z"), List.of(new ExportBook.Chapter("O mapa", JSON.readTree(doc))),
                java.util.Map.of(IMAGE_ID, new ImageData(PNG, contentType, contentType.endsWith("png") ? "png" : "webp")));
    }

    @Test
    void illustrationGoesIntoTheEpubAndPassesEpubcheck(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(illustrated("image/png"));
        Path file = dir.resolve("ilustrado.epub");
        Files.write(file, epub);

        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
        assertThat(entries(epub)).contains("EPUB/images/ill-" + IMAGE_ID + ".png");
        assertThat(entry(epub, "EPUB/chapter-001.xhtml"))
                .contains("<img src=\"images/ill-" + IMAGE_ID + ".png\" alt=\"O mapa de Vel\"/>")
                .contains("<figcaption>O mapa de Vel</figcaption>");
    }

    @Test
    void illustrationWithoutImageOrCaptionDisappears() {
        String html = illustrated("image/png").chapters().getFirst().html();
        // Sem endereço de imagem: só a legenda; a segunda (sem imagem e sem legenda) some
        assertThat(html).contains("<figcaption>O mapa de Vel</figcaption>").doesNotContain("<img").containsOnlyOnce("<figure");
    }

    @Test
    void pdfEmbedsPngIllustrationsButNotWebp() {
        assertThat(PdfWriter.imageUri(illustrated("image/png"), IMAGE_ID)).isEqualTo("booksmith-img:" + IMAGE_ID);
        assertThat(PdfWriter.imageUri(illustrated("image/webp"), IMAGE_ID)).isNull();
        assertThat(PdfWriter.write(illustrated("image/png"))).isNotEmpty();
    }

    @Test
    void wordManuscriptGetsTheRealImageAndAPlaceholderWhenThereIsNone() throws Exception {
        byte[] docx = DocxWriter.write(illustrated("image/png"));
        String document = entry(docx, "word/document.xml");
        // A imagem vai de verdade, com a legenda embaixo; a que não está no livro vira o marcador
        assertThat(document).contains("<a:blip r:embed=\"rIdImg1\"/>").contains("descr=\"O mapa de Vel\"")
                .contains(">O mapa de Vel</w:t>").contains("[Ilustração]").doesNotContain("[Ilustração: O mapa de Vel]");
        assertThat(entries(docx)).contains("word/media/imagem-1.png");
        assertThat(entry(docx, "word/_rels/document.xml.rels")).contains("Id=\"rIdImg1\"").contains("Target=\"media/imagem-1.png\"");
        assertThat(entry(docx, "[Content_Types].xml")).contains("Extension=\"png\"");
        // O arquivo continua voltando pela importação (XML bem formado, o texto e a legenda vêm junto)
        assertThat(com.booksmith.manuscript.internal.ImportRoundTrip.plan(docx)).containsExactly("O mapa|");
    }

    /** PNG de {@code w}×{@code h}: metade esquerda vermelha, direita azul. */
    private static byte[] twoColors(int w, int h) throws Exception {
        var image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(java.awt.Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(java.awt.Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        return png.toByteArray();
    }

    private static ExportBook placed(byte[] png, String... sizes) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < sizes.length; i++) {
            content.append("{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Texto antes %d.\"}]},".formatted(i));
            content.append("{\"type\":\"illustration\",\"attrs\":{\"imageId\":\"%s\",\"caption\":\"Arte %d\",\"size\":\"%s\"%s}},"
                    .formatted(IMAGE_ID, i, sizes[i], sizes[i].equals("small") ? ",\"align\":\"left\"" : ""));
        }
        content.append("{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Fim.\"}]}");
        return new ExportBook(UUID.randomUUID(), "Ilustrado", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-16T12:00:00Z"),
                List.of(new ExportBook.Chapter("Um", JSON.readTree("{\"type\":\"doc\",\"content\":[" + content + "]}"))),
                java.util.Map.of(IMAGE_ID, new ImageData(png, "image/png", "png")));
    }

    @Test
    void illustrationPlacementsInTheHtml() throws Exception {
        String html = PdfWriter.html(placed(twoColors(2000, 1500), "small", "page", "bleed", "spread"));
        assertThat(html).contains("<figure class=\"illustration size-small align-left\">")
                .contains("<div class=\"illustration-page\"").contains("<p class=\"illustration-caption\">Arte 1</p>")
                .contains("<div class=\"bleed-page spread-left\">");
        // A5 (148 × 210): a imagem 4:3 cobrindo a página tem 210 mm de altura e 280 de largura; corta 66 de cada lado
        assertThat(html).contains("width: 148.0mm; height: 209.0mm").contains("width: 280.0mm; height: 210.0mm; margin-left: -66.0mm");
        // Dupla: 296 × 210 coberto por 296 × 222; a página da direita começa onde a da esquerda acabou
        assertThat(html).contains("width: 296.0mm; height: 222.0mm; margin-left: -0.0mm; margin-top: -6.0mm")
                .contains("width: 296.0mm; height: 222.0mm; margin-left: -148.0mm; margin-top: -6.0mm");

        // No ePub (sem quem desenhe a página), a figura comum com a classe do tamanho
        String epub = placed(twoColors(2000, 1500), "bleed").chapters().getFirst().html(id -> "images/x.png");
        assertThat(epub).contains("<figure class=\"illustration size-bleed\">");
    }

    @Test
    void bleedAndSpreadFillThePagesInThePdf() throws Exception {
        byte[] pdf = PdfWriter.write(placed(twoColors(2000, 1500), "bleed", "spread"));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            List<Integer> imagePages = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                var page = renderer.renderImageWithDPI(i, 36);
                var corner = new java.awt.Color(page.getRGB(1, page.getHeight() - 2));
                if (stripper.getText(document).isBlank() && (corner.getRed() > 200 || corner.getBlue() > 200)) {
                    imagePages.add(i + 1);
                }
            }
            // Até a borda: uma página; dupla: duas seguidas, a primeira par (esquerda do livro aberto)
            assertThat(imagePages).hasSize(3);
            int left = imagePages.get(1);
            assertThat(left % 2).isZero();
            assertThat(imagePages.get(2)).isEqualTo(left + 1);
            // A esquerda da dupla mostra a metade vermelha; a direita, a azul, até as bordas
            var leftPage = renderer.renderImageWithDPI(left - 1, 36);
            var rightPage = renderer.renderImageWithDPI(left, 36);
            for (int[] at : new int[][] {{1, 1}, {leftPage.getWidth() - 2, leftPage.getHeight() / 2}}) {
                assertThat(new java.awt.Color(leftPage.getRGB(at[0], at[1])).getRed()).isGreaterThan(200);
                assertThat(new java.awt.Color(rightPage.getRGB(at[0], at[1])).getBlue()).isGreaterThan(200);
            }
        }
    }

    @Test
    void squareBookWithBleedIsThePrintInterior() throws Exception {
        var layout = new com.booksmith.manuscript.api.BookLayout("20x20", null, null, null, null, null, null, null, true);
        ExportBook book = placed(twoColors(2400, 2400), "bleed").withLayout(layout);
        byte[] pdf = PdfWriter.write(book);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            float mm = 72f / 25.4f;
            var first = document.getPage(0);
            // 200 × 200 mm + 3,2 na borda de fora + 3,2 em cima e embaixo
            assertThat(first.getMediaBox().getWidth()).isCloseTo(203.2f * mm, org.assertj.core.data.Offset.offset(0.5f));
            assertThat(first.getMediaBox().getHeight()).isCloseTo(206.4f * mm, org.assertj.core.data.Offset.offset(0.5f));
            // Corte: na página da direita a sangria fica à direita; na da esquerda, à esquerda
            assertThat(first.getTrimBox().getLowerLeftX()).isZero();
            assertThat(document.getPage(1).getTrimBox().getLowerLeftX()).isCloseTo(3.2f * mm, org.assertj.core.data.Offset.offset(0.1f));
            assertThat(first.getTrimBox().getWidth()).isCloseTo(200f * mm, org.assertj.core.data.Offset.offset(0.5f));
            assertThat(first.getBleedBox().toString()).isEqualTo(first.getMediaBox().toString());

            // O texto fica no mesmo lugar em relação ao corte: na página par, 3,2 mm mais para a direita
            float[] left = new float[document.getNumberOfPages()];
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    int page = getCurrentPageNo() - 1;
                    for (var p : positions) {
                        left[page] = left[page] == 0 ? p.getXDirAdj() : Math.min(left[page], p.getXDirAdj());
                    }
                }
            };
            stripper.getText(document);
            // Página 2 (esquerda): o sumário começa a 17 + 3,2 mm da borda; página 3 (direita): o capítulo, a 17 mm
            assertThat(left[1]).isCloseTo(20.2f * mm, org.assertj.core.data.Offset.offset(1f));
            assertThat(left[2]).isCloseTo(17f * mm, org.assertj.core.data.Offset.offset(1f));
        }
        // Sem capa no miolo para gráfica
        assertThat(PdfWriter.hasCoverPage(book)).isFalse();
        // A ilustração até a borda cobre a página com a sangria (203,2 × 206,4)
        assertThat(PdfWriter.html(book)).contains("width: 203.2mm; height: 205.4mm");
    }

    private static ExportBook pictureBook() throws Exception {
        var layout = new com.booksmith.manuscript.api.BookLayout("20x20", null, null, null, null, null, null, null, true);
        String text = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"%s\"}]}]}";
        var bottom = new com.booksmith.manuscript.api.PageDesign(IMAGE_ID, null, "bottom", "center", "l", "dark", true, false, null);
        var spread = new com.booksmith.manuscript.api.PageDesign(IMAGE_ID, null, "top", "left", "m", "light", false, true, "right");
        var middle = new com.booksmith.manuscript.api.PageDesign(null, "#ffd23f", "middle", "center", "xl", "dark", false, false, null);
        List<ExportBook.Chapter> chapters = new ArrayList<>();
        int n = 0;
        for (var page : List.of(bottom, spread, middle)) {
            n++;
            var doc = PictureBookHtml.withBackground(JSON.readTree(text.formatted("Página " + n + " da história.")), page);
            chapters.add(new ExportBook.Chapter("Página " + n, doc, null, page));
        }
        return new ExportBook(UUID.randomUUID(), "O dia colorido", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-16T12:00:00Z"), chapters,
                java.util.Map.of(IMAGE_ID, new ImageData(twoColors(2400, 2400), "image/png", "png")),
                com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY, layout, true);
    }

    @Test
    void pictureBookPagesHaveTheArtToTheEdgeAndTheTextOnTop() throws Exception {
        ExportBook book = pictureBook();
        byte[] pdf = PdfWriter.write(book);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            // Folha de rosto, página 1, dupla (esquerda par + direita), página 3
            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                texts.add(stripper.getText(document).strip());
            }
            int first = texts.indexOf("Página 1 da história.");
            int third = texts.indexOf("Página 3 da história.");
            // Como num miolo de editora: folha de rosto, créditos (com o copyright), página de rosto; depois a história
            assertThat(texts.getFirst()).contains("O dia colorido");
            assertThat(texts.get(1)).contains("Copyright ©").contains("Todos os direitos reservados.");
            assertThat(texts.get(2)).contains("O dia colorido").contains("Autora");
            assertThat(first).isEqualTo(3);
            // O livro fecha em múltiplo de 4 (páginas em branco no fim)
            assertThat(document.getNumberOfPages() % 4).isZero();
            // A dupla: duas páginas seguidas, a primeira par (índice ímpar = página par), texto só na da direita
            int spreadLeft = third - 2;
            assertThat((spreadLeft + 1) % 2).isZero();
            assertThat(texts.get(spreadLeft)).isEmpty();
            assertThat(texts.get(spreadLeft + 1)).isEqualTo("Página 2 da história.");

            // Fundo até a borda: os cantos da página 1 têm a arte (vermelho à esquerda, azul à direita)
            var page1 = renderer.renderImageWithDPI(first, 36);
            assertThat(new java.awt.Color(page1.getRGB(1, 1)).getRed()).isGreaterThan(200);
            assertThat(new java.awt.Color(page1.getRGB(page1.getWidth() - 2, 1)).getBlue()).isGreaterThan(200);
            // Dupla: a esquerda é toda vermelha, a direita toda azul
            var left = renderer.renderImageWithDPI(spreadLeft, 36);
            var right = renderer.renderImageWithDPI(spreadLeft + 1, 36);
            assertThat(new java.awt.Color(left.getRGB(left.getWidth() - 2, left.getHeight() - 2)).getRed()).isGreaterThan(200);
            assertThat(new java.awt.Color(right.getRGB(1, 1)).getBlue()).isGreaterThan(200);
            // Página 3: só a cor de fundo (amarelo) e o texto no meio
            var page3 = renderer.renderImageWithDPI(third, 36);
            var corner = new java.awt.Color(page3.getRGB(2, 2));
            assertThat(corner.getRed()).isGreaterThan(240);
            assertThat(corner.getGreen()).isGreaterThan(190);
            assertThat(corner.getBlue()).isLessThan(100);

            // Posição do texto: embaixo na página 1, no meio da página 3 (em pontos, de cima)
            float[] firstY = textY(document, first + 1);
            float[] thirdY = textY(document, third + 1);
            float height = document.getPage(first).getMediaBox().getHeight();
            assertThat(firstY[0] / height).isGreaterThan(0.75f);
            assertThat(thirdY[0] / height).isBetween(0.35f, 0.65f);
        }
        // Sem sumário nem número de página; o texto da página não repete a ilustração de fundo
        String html = PictureBookHtml.html(book);
        assertThat(html).doesNotContain("Sumário").doesNotContain("<figure");
        assertThat(html).contains("pb-text dark band-light align-center").contains("pb-page spread-left");
        // A área segura conta do corte: na página da esquerda (a 2) a sangria fica à esquerda (12 + 3,2 mm); na da
        // direita (a 5, metade direita da dupla), à direita. A caixa tem 200 − 24 = 176 mm nas duas
        assertThat(html).contains("left: 15.2mm; width: 176.0mm").contains("left: 12.0mm; width: 176.0mm");
    }

    private static ExportBook withFootnotes() {
        String doc = "{\"type\":\"doc\",\"content\":["
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Ele orou.\"},"
                + "{\"type\":\"footnote\",\"attrs\":{\"text\":\"Mateus 6:9.\"}},"
                + "{\"type\":\"text\",\"text\":\" E esperou.\"},"
                + "{\"type\":\"footnote\",\"attrs\":{\"text\":\"Salmo 27:14 & <outros>.\"}}]}]}";
        return new ExportBook(UUID.randomUUID(), "Notas", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-17T12:00:00Z"), List.of(new ExportBook.Chapter("Oração", JSON.readTree(doc))));
    }

    @Test
    void footnotesInTheEpubOpenFromTheNumberAndPassEpubcheck(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(withFootnotes());
        Path file = dir.resolve("notas.epub");
        Files.write(file, epub);
        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
        String chapter = entry(epub, "EPUB/chapter-001.xhtml");
        assertThat(chapter).contains("<a epub:type=\"noteref\" class=\"noteref\" id=\"chapter-1-fnref1\" href=\"#chapter-1-fn1\">1</a>")
                .contains("<aside epub:type=\"footnote\" class=\"footnote\" id=\"chapter-1-fn2\"><p><a href=\"#chapter-1-fnref2\">2</a> Salmo 27:14 &amp; &lt;outros&gt;.</p></aside>");
    }

    @Test
    void footnotesGoToTheBottomOfThePdfPage() throws Exception {
        byte[] pdf = PdfWriter.write(withFootnotes());
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int last = document.getNumberOfPages();
            float height = document.getPage(last - 1).getMediaBox().getHeight();
            float[] noteY = {-1};
            float[] bodyY = {-1};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    if (text.contains("Mateus 6:9")) {
                        noteY[0] = positions.getFirst().getYDirAdj();
                    } else if (text.contains("Ele orou")) {
                        bodyY[0] = positions.getFirst().getYDirAdj();
                    }
                }
            };
            stripper.setStartPage(last);
            stripper.setEndPage(last);
            stripper.getText(document);
            // O texto do capítulo no alto da página; a nota no pé dela
            assertThat(bodyY[0]).isPositive();
            assertThat(noteY[0] / height).isGreaterThan(0.75f);
            assertThat(noteY[0]).isGreaterThan(bodyY[0]);
        }
    }

    @Test
    void wordGetsRealFootnotesThatComeBackOnImport() throws Exception {
        byte[] docx = DocxWriter.write(withFootnotes());
        String document = entry(docx, "word/document.xml");
        assertThat(document).contains("<w:footnoteReference w:id=\"1\"/>").contains("<w:footnoteReference w:id=\"2\"/>");
        assertThat(entry(docx, "word/footnotes.xml")).contains("w:id=\"-1\"").contains("Mateus 6:9.")
                .contains("Salmo 27:14 &amp; &lt;outros&gt;.");
        assertThat(entry(docx, "word/_rels/document.xml.rels")).contains("Target=\"footnotes.xml\"");
        // Importar o Word de volta: as notas voltam como notas, no mesmo lugar
        String imported = com.booksmith.manuscript.internal.ImportRoundTrip.documents(docx).getFirst();
        assertThat(imported).contains("{\"type\":\"footnote\",\"attrs\":{\"text\":\"Mateus 6:9.\"}}")
                .contains("{\"type\":\"footnote\",\"attrs\":{\"text\":\"Salmo 27:14 & <outros>.\"}}");
    }

    @Test
    void freeTextGoesWhereTheAuthorDraggedIt() throws Exception {
        var layout = new com.booksmith.manuscript.api.BookLayout("20x20", null, null, null, null, null, null, null, true);
        String text = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Livre.\"}]}]}";
        // Dupla (400 mm de largura): caixa de 30% (120 mm) começando em 60% (240 mm): o meio está na página da direita,
        // a 40 mm do corte dela; 25% de altura = 50 mm, mais 3,2 de sangria
        var spread = new com.booksmith.manuscript.api.PageDesign(null, null, "free", "left", "m", "dark", false, true,
                null, 60.0, 25.0, 30.0);
        // Página simples: 10% e 70% = 20 mm (+3,2 de sangria: é a página da esquerda) e 140 mm; largura 50% = 100 mm
        var single = new com.booksmith.manuscript.api.PageDesign(null, null, "free", "left", "m", "dark", false, false,
                null, 10.0, 70.0, 50.0);
        var book = new ExportBook(UUID.randomUUID(), "Livre", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-16T12:00:00Z"),
                List.of(new ExportBook.Chapter("Página 1", JSON.readTree(text), null, single),
                        new ExportBook.Chapter("Página 2", JSON.readTree(text), null, spread)),
                java.util.Map.of(), com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY, layout, true);
        String html = PictureBookHtml.html(book);
        assertThat(html).contains("left: 23.2mm; width: 100.0mm; top: 143.2mm")
                .contains("left: 40.0mm; width: 120.0mm; top: 53.2mm");
        assertThat(PdfWriter.write(book)).isNotEmpty();
        // Fora da página, a caixa é trazida de volta: largura no máximo 100%, borda até onde ela cabe
        var out = new com.booksmith.manuscript.api.PageDesign(null, null, "free", null, null, null, null, null, null,
                95.0, 150.0, 40.0);
        assertThat(out.x()).isEqualTo(60.0);
        assertThat(out.y()).isEqualTo(95.0);
    }

    @Test
    void artZoomAndPositionAndTheAuthorBuildsEveryPage() throws Exception {
        // 17 × 24 sem sangria, sem as páginas automáticas, com número de página
        var layout = new com.booksmith.manuscript.api.BookLayout("17x24", null, null, null, null, null, null, null, false,
                false, true);
        String text = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Capa.\"}]}]}";
        // Arte quadrada 2400 × 2400 numa página 170 × 240: cobrir dá 240 × 240; zoom 2 = 480 × 480, centro em (25%, 50%)
        var zoomed = new com.booksmith.manuscript.api.PageDesign(IMAGE_ID, null, "top", null, null, null, null, null, null,
                null, null, null, 2.0, 25.0, 50.0);
        var book = new ExportBook(UUID.randomUUID(), "Hiper", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-17T12:00:00Z"),
                List.of(new ExportBook.Chapter("Capa", PictureBookHtml.withBackground(JSON.readTree(text), zoomed), null, zoomed)),
                java.util.Map.of(IMAGE_ID, new ImageData(twoColors(2400, 2400), "image/png", "png")),
                com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY, layout, true);
        String html = PictureBookHtml.html(book);
        // centro em 42,5 mm: a imagem começa em 42,5 − 240 = −197,5; em cima, 120 − 240 = −120
        assertThat(html).contains("width: 480.0mm; height: 480.0mm; margin-left: -197.5mm; margin-top: -120.0mm");
        // Sem folha de rosto: a primeira página é a do autor, número 1, no canto de fora (direita)
        assertThat(html).doesNotContain("class=\"title-page\"").contains("<div class=\"pb-number dark\" style=\"right: 8.0mm; bottom: 6.0mm\">1</div>");
        try (PDDocument document = Loader.loadPDF(PdfWriter.write(book))) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
        // A conferência conta o zoom: o DPI cai pela metade
        var check = PrintCheck.check(book);
        var plain = PrintCheck.check(pictureBook());
        assertThat(check.illustrations().getFirst().dpi()).isEqualTo((int) Math.floor(2400 / (240 / 25.4) / 2 + 0.001));
        assertThat(plain.illustrations().getFirst().dpi()).isGreaterThan(check.illustrations().getFirst().dpi());
    }

    @Test
    void pictureBookEscapesTheAuthorsText() throws Exception {
        String text = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"<script>alert(1)</script> & cia\"}]}]}";
        var book = new ExportBook(UUID.randomUUID(), "<b>Título</b>", "Ana & <i>Bia</i>", "pt-BR", null, null, null,
                Instant.parse("2026-11-17T12:00:00Z"), List.of(new ExportBook.Chapter("Página 1", JSON.readTree(text), null,
                com.booksmith.manuscript.api.PageDesign.DEFAULT)), java.util.Map.of(),
                new com.booksmith.manuscript.api.Manuscripts.BookPages("Créditos <x>", "Para <y>", null, null, null, null, null),
                com.booksmith.manuscript.api.BookLayout.DEFAULT, true);
        String html = PictureBookHtml.html(book);
        assertThat(html).doesNotContain("<script>").doesNotContain("<b>Título").doesNotContain("<i>Bia")
                .doesNotContain("<x>").doesNotContain("<y>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; cia").contains("Ana &amp; &lt;i&gt;Bia&lt;/i&gt;");
    }

    @Test
    void pictureBookBackgroundsShowInTheEpubAndAreCheckedForPrint() throws Exception {
        ExportBook book = pictureBook();
        String page1 = book.chapters().getFirst().html(id -> "images/x.png");
        assertThat(page1).contains("<figure class=\"illustration size-bleed\">");
        var check = PrintCheck.check(book);
        // Duas páginas com imagem: até a borda e dupla, medidas no tamanho da página com sangria
        assertThat(check.illustrations()).extracting(PrintCheck.Illustration::size).containsExactly("bleed", "spread");
        assertThat(check.needsBleed()).isFalse();
    }

    /** A altura (de cima) do primeiro caractere da página, em pontos. */
    private static float[] textY(PDDocument document, int page) throws Exception {
        float[] y = {-1};
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                if (y[0] < 0 && !positions.isEmpty()) {
                    y[0] = positions.getFirst().getYDirAdj();
                }
            }
        };
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.getText(document);
        return y;
    }

    @Test
    void textKeepsItsPlaceRelativeToTheCutOnLeftAndRightPages() throws Exception {
        var layout = new com.booksmith.manuscript.api.BookLayout("a5", null, null, null, null, null, null, null, true);
        String css = PdfWriter.layoutCss(layout);
        // Margens normais (17 mm dos lados): a de fora ganha a sangria
        assertThat(css).contains("@page :right { margin-left: 17mm; margin-right: 20.2mm; }")
                .contains("@page :left { margin-left: 20.2mm; margin-right: 17mm; }")
                .contains("size: 151.2mm 216.4mm; margin: 23.2mm 17mm 25.2mm 17mm;");
        // Sem sangria, igual a antes
        assertThat(PdfWriter.layoutCss(com.booksmith.manuscript.api.BookLayout.DEFAULT))
                .contains("size: 148mm 210mm; margin: 20mm 17mm 22mm 17mm;").doesNotContain("@page :left");
    }

    @Test
    void printCheckSaysWhenEdgeIllustrationsNeedBleed() throws Exception {
        assertThat(PrintCheck.check(placed(twoColors(2000, 1500), "spread")).needsBleed()).isTrue();
        assertThat(PrintCheck.check(placed(twoColors(2000, 1500), "wide")).needsBleed()).isFalse();
        var withBleed = new com.booksmith.manuscript.api.BookLayout("a5", null, null, null, null, null, null, null, true);
        assertThat(PrintCheck.check(placed(twoColors(2000, 1500), "spread").withLayout(withBleed)).needsBleed()).isFalse();
    }

    @Test
    void printCheckMeasuresEachPlacement() throws Exception {
        var result = PrintCheck.check(placed(twoColors(2000, 1500), "small", "wide", "bleed", "spread"));
        // A5, margens normais: texto com 114 mm
        assertThat(result.illustrations()).extracting(PrintCheck.Illustration::size)
                .containsExactly("small", "wide", "bleed", "spread");
        assertThat(result.illustrations().get(0).widthMm()).isEqualTo(45.6);
        assertThat(result.illustrations().get(1).widthMm()).isEqualTo(114.0);
        // Cobrindo a página, 2000 px em 280 mm: 181 DPI; na dupla, 2000 px em 296 mm: 171 DPI
        assertThat(result.illustrations().get(2).dpi()).isEqualTo(181);
        assertThat(result.illustrations().get(3).dpi()).isEqualTo(171);
        assertThat(result.illustrations().get(3).ok()).isFalse();
    }

    private static ExportBook withParts() {
        String doc = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"id\":\"b_p1\"},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"Texto.\"}]}]}";
        return new ExportBook(UUID.randomUUID(), "A Conversão", "Sérgio Holovati", "pt-BR", null, null, null,
                Instant.parse("2026-11-10T12:00:00Z"), List.of(
                new ExportBook.Chapter("Prólogo", JSON.readTree(doc)),
                new ExportBook.Chapter("O Encontro", JSON.readTree(doc), "Parte I — A partida"),
                new ExportBook.Chapter("A Estrada", JSON.readTree(doc)),
                new ExportBook.Chapter("O Retorno", JSON.readTree(doc), "Parte II — A volta")));
    }

    @Test
    void partsGetTheirOwnPageAndANestedTocInTheEpub(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(withParts());
        Path file = dir.resolve("partes.epub");
        Files.write(file, epub);

        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
        assertThat(entries(epub)).contains("EPUB/part-002.xhtml", "EPUB/part-004.xhtml").doesNotContain("EPUB/part-001.xhtml");
        String nav = entry(epub, "EPUB/nav.xhtml");
        // O prólogo fica solto; "A Estrada" fica dentro da Parte I
        assertThat(nav.indexOf("Prólogo")).isLessThan(nav.indexOf("Parte I"));
        assertThat(nav.substring(nav.indexOf("Parte I"), nav.indexOf("Parte II"))).contains("A Estrada").contains("<ol>");
    }

    private static ExportBook withSubchapters() {
        String sections = "{\"type\":\"doc\",\"content\":["
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Abertura.\"}]},"
                + "{\"type\":\"heading\",\"attrs\":{\"level\":2},\"content\":[{\"type\":\"text\",\"text\":\"1.1 - Natureza divina\"}]},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Texto.\"}]},"
                + "{\"type\":\"heading\",\"attrs\":{\"level\":3},\"content\":[{\"type\":\"text\",\"text\":\"Detalhe pequeno\"}]},"
                + "{\"type\":\"heading\",\"attrs\":{\"level\":2},\"content\":[{\"type\":\"text\",\"text\":\"1.2 - O \"},"
                + "{\"type\":\"text\",\"marks\":[{\"type\":\"italic\"}],\"text\":\"poder\"}]}]}";
        String plain = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Texto.\"}]}]}";
        return new ExportBook(UUID.randomUUID(), "Legado", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-16T12:00:00Z"), List.of(
                new ExportBook.Chapter("Introdução", JSON.readTree(plain)),
                new ExportBook.Chapter("Capítulo 1 - Seja cheio", JSON.readTree(sections), "Parte 1"),
                new ExportBook.Chapter("Capítulo 2 - O legado", JSON.readTree(plain))));
    }

    @Test
    void subchaptersGoIntoTheTocOfTheEpubAndThePdf(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(withSubchapters());
        Path file = dir.resolve("subcapitulos.epub");
        Files.write(file, epub);
        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();

        String nav = entry(epub, "EPUB/nav.xhtml");
        assertThat(nav).contains("chapter-002.xhtml#chapter-2-s1\">1.1 - Natureza divina")
                .contains("chapter-002.xhtml#chapter-2-s3\">1.2 - O poder")
                .doesNotContain("Detalhe pequeno");
        assertThat(entry(epub, "EPUB/chapter-002.xhtml")).contains("<h3 id=\"chapter-2-s1\">1.1 - Natureza divina</h3>")
                .contains("<h4 id=\"chapter-2-s2\">");

        String html = PdfWriter.html(withSubchapters());
        assertThat(html).contains("<li class=\"toc-section\"><a href=\"#chapter-2-s1\">1.1 - Natureza divina</a></li>")
                .contains("<h3 id=\"chapter-2-s3\">");
        assertThat(PdfWriter.write(withSubchapters())).isNotEmpty();
    }

    @Test
    void wordPartComesBackAsAPartWhenImported() throws Exception {
        byte[] docx = DocxWriter.write(withParts());
        assertThat(entry(docx, "word/document.xml")).contains("<w:pStyle w:val=\"Parte\"/>");

        var plan = com.booksmith.manuscript.internal.ImportRoundTrip.plan(docx);

        assertThat(plan).containsExactly("Prólogo|", "O Encontro|Parte I — A partida", "A Estrada|", "O Retorno|Parte II — A volta");
    }

    private static ExportBook withPages(String aboutTitle) {
        ExportBook base = withParts();
        var pages = new com.booksmith.manuscript.api.Manuscripts.BookPages(
                "Copyright © 2026 Lia Moreno\r\nCapa: Ana & Bia", "  Para minha avó,\n\nque contava histórias.  ",
                "Não há café sem conversa.", "Provérbio de Vel", "Obrigado a quem leu o primeiro rascunho.",
                "Escreve à noite, com café.", aboutTitle);
        return new ExportBook(base.id(), base.title(), base.author(), base.language(), null, null, null,
                base.generatedAt(), base.chapters(), java.util.Map.of(), pages);
    }

    private static ExportBook withFullStructure() throws Exception {
        ExportBook base = withParts();
        var pages = new com.booksmith.manuscript.api.Manuscripts.BookPages("Copyright © 2026 Lia", "Para Vel.", null,
                null, null, "Escreve à noite.", "Sobre a autora", true,
                "Dados Internacionais de Catalogação na Publicação (CIP)\nMoreno, Lia\nCDD 869.3", "Composto em Literata.",
                UUID.fromString(IMAGE_ID));
        return new ExportBook(base.id(), base.title(), base.author(), base.language(), null, null, null,
                base.generatedAt(), base.chapters(),
                java.util.Map.of(IMAGE_ID, new ImageData(twoColors(400, 400), "image/png", "png")), pages);
    }

    private static ExportBook withCallout() {
        String doc = "{\"type\":\"doc\",\"content\":[{\"type\":\"callout\",\"attrs\":{\"kind\":\"verse\"},\"content\":["
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Ore sem cessar. 1 Ts 5:17\"}]}]},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Texto.\"}]}]}";
        return new ExportBook(UUID.randomUUID(), "Oração", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-17T12:00:00Z"), List.of(new ExportBook.Chapter("Dia 1", JSON.readTree(doc))));
    }

    @Test
    void calloutsAreFramedBoxesInEpubPdfAndWord(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(withCallout());
        Path file = dir.resolve("destaque.epub");
        Files.write(file, epub);
        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
        assertThat(entry(epub, "EPUB/chapter-001.xhtml")).contains("<div class=\"callout callout-verse\">")
                .contains("<p class=\"callout-title\">Versículo-chave</p>");
        assertThat(PdfWriter.html(withCallout())).contains("<div class=\"callout callout-verse\">");
        assertThat(PdfWriter.write(withCallout())).isNotEmpty();
        String document = entry(DocxWriter.write(withCallout()), "word/document.xml");
        assertThat(document).contains("<w:pStyle w:val=\"Destaque\"/>").contains("Versículo-chave").contains("1 Ts 5:17");
    }

    @Test
    void fullBookStructureInTheEpubAndPdf(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(withFullStructure());
        Path file = dir.resolve("estrutura.epub");
        Files.write(file, epub);
        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
        assertThat(entry(epub, "EPUB/half-title.xhtml")).contains("A Conversão");
        assertThat(entry(epub, "EPUB/credits.xhtml")).contains("<div class=\"catalog-card\">").contains("CDD 869.3");
        assertThat(entry(epub, "EPUB/about-author.xhtml")).contains("<img src=\"images/ill-" + IMAGE_ID + ".png\"");
        assertThat(entry(epub, "EPUB/colophon.xhtml")).contains("Composto em Literata.");

        String html = PdfWriter.html(withFullStructure());
        // Falsa folha de rosto antes dos créditos; a foto na página "sobre"; o colofão no fim
        assertThat(html.indexOf("class=\"half-title\"")).isPositive().isLessThan(html.indexOf("class=\"credits\""));
        assertThat(html).contains("<p class=\"author-photo\"><img src=\"booksmith-img:");
        assertThat(html.lastIndexOf("Composto em Literata.")).isGreaterThan(html.indexOf("Escreve à noite."));
        assertThat(PdfWriter.write(withFullStructure())).isNotEmpty();
    }

    @Test
    void fullBookStructureInTheWord() throws Exception {
        byte[] docx = DocxWriter.write(withFullStructure());
        String document = entry(docx, "word/document.xml");
        assertThat(document).contains("CDD 869.3").contains("Composto em Literata.").contains("rIdImg1");
        // O Word exportado volta pela importação: a falsa folha de rosto e a ficha ficam na capa, não viram capítulo
        assertThat(com.booksmith.manuscript.internal.ImportRoundTrip.plan(docx))
                .containsExactly("Prólogo|", "O Encontro|Parte I — A partida", "A Estrada|", "O Retorno|Parte II — A volta");
    }

    @Test
    void bookPagesGoBeforeAndAfterTheStoryInTheEpub(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(withPages(null));
        Path file = dir.resolve("paginas.epub");
        Files.write(file, epub);

        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
        String opf = entry(epub, "EPUB/package.opf");
        // Ordem de livro: rosto, créditos, dedicatória, epígrafe, capítulos, agradecimentos, sobre
        assertThat(opf).containsSubsequence("idref=\"title\"", "idref=\"credits\"", "idref=\"dedication\"",
                "idref=\"epigraph\"", "idref=\"chapter-1\"", "idref=\"chapter-4\"", "idref=\"acknowledgments\"",
                "idref=\"about-author\"");
        assertThat(entry(epub, "EPUB/credits.xhtml")).contains("<p>Copyright © 2026 Lia Moreno</p>\n<p>Capa: Ana &amp; Bia</p>");
        assertThat(entry(epub, "EPUB/dedication.xhtml")).contains("<p>Para minha avó,</p>\n<p>que contava histórias.</p>");
        assertThat(entry(epub, "EPUB/epigraph.xhtml")).contains("— Provérbio de Vel");
        // Sem título escolhido, a página "sobre" leva o nome de quem escreveu; só ela e os agradecimentos vão ao sumário
        String nav = entry(epub, "EPUB/nav.xhtml");
        assertThat(nav).containsSubsequence("O Retorno", "Agradecimentos", "Sérgio Holovati").doesNotContain("Dedicatória");
        assertThat(entry(epub, "EPUB/about-author.xhtml")).contains("<h1>Sérgio Holovati</h1>");
    }

    @Test
    void bookPagesInThePdf() throws Exception {
        byte[] pdf = PdfWriter.write(withPages("Sobre a autora"));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).containsSubsequence("Capa: Ana & Bia", "Para minha avó,", "Não há café sem conversa.",
                    "Sumário", "Sobre a autora", "Texto.", "Obrigado a quem leu", "Escreve à noite");
            List<String> bookmarks = new ArrayList<>();
            for (PDOutlineItem item : document.getDocumentCatalog().getDocumentOutline().children()) {
                bookmarks.add(item.getTitle());
            }
            assertThat(bookmarks).endsWith("Agradecimentos", "Sobre a autora");
        }
    }

    private static ExportBook withIsbn() {
        ExportBook base = withPages(null);
        return new ExportBook(base.id(), base.title(), base.author(), base.language(), null, null, null,
                base.generatedAt(), base.chapters(), java.util.Map.of(),
                base.pages().withIsbn("978-65-00-12345-6", "978-65-00-12346-3"));
    }

    @Test
    void theEbookIsbnClosesTheCreditsAndIdentifiesTheEpub(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(withIsbn());
        Path file = dir.resolve("isbn.epub");
        Files.write(file, epub);

        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
        assertThat(entry(epub, "EPUB/credits.xhtml"))
                .containsSubsequence("Capa: Ana &amp; Bia", "<p class=\"isbn\">ISBN 978-65-00-12346-3</p>")
                .doesNotContain("12345-6");
        assertThat(entry(epub, "EPUB/package.opf")).contains("<dc:identifier id=\"book-id\">urn:isbn:9786500123463</dc:identifier>");
        assertThat(entry(epub, "EPUB/toc.ncx")).contains("content=\"urn:isbn:9786500123463\"");
    }

    @Test
    void thePrintIsbnGoesInThePdfCredits() throws Exception {
        try (PDDocument document = Loader.loadPDF(PdfWriter.write(withIsbn()))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).containsSubsequence("Capa: Ana & Bia", "ISBN 978-65-00-12345-6", "Sumário")
                    .doesNotContain("12346-3");
        }
    }

    @Test
    void anIsbnAloneMakesTheCreditsPage() {
        var pages = com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY.withIsbn("978-65-00-12345-6", null);
        assertThat(BookPagesHtml.front(pages, pages.isbnPrint())).singleElement()
                .satisfies(page -> assertThat(page.html()).contains("<p class=\"isbn\">ISBN 978-65-00-12345-6</p>"));
        assertThat(BookPagesHtml.front(pages, pages.isbnEbook())).isEmpty();
    }

    @Test
    void wordBookPagesDoNotBecomeChaptersWhenImported() throws Exception {
        byte[] docx = DocxWriter.write(withPages(null));
        String document = entry(docx, "word/document.xml");
        assertThat(document).contains("Para minha avó,").contains("<w:pStyle w:val=\"PosTextual\"/>")
                .contains("Escreve à noite, com café.");

        var plan = com.booksmith.manuscript.internal.ImportRoundTrip.plan(docx);

        assertThat(plan).containsExactly("Prólogo|", "O Encontro|Parte I — A partida", "A Estrada|", "O Retorno|Parte II — A volta");
    }

    @Test
    void layoutDefaultsAndRejectsUnknownValues() {
        var layout = com.booksmith.manuscript.api.BookLayout.DEFAULT;
        assertThat(layout.trim()).isEqualTo("a5");
        assertThat(layout.fontSize()).isEqualTo(11.0);
        assertThat(layout.margins()).isEqualTo("normal");
        assertThat(layout.chapterStyle()).isEqualTo("classic");
        assertThat(layout.sceneBreak()).isEqualTo("asterisks");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new com.booksmith.manuscript.api.BookLayout("a4", null, null, null, null, null))
                .isInstanceOfSatisfying(com.booksmith.common.error.ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("layout_invalid"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new com.booksmith.manuscript.api.BookLayout(null, 13.0, null, null, null, null))
                .isInstanceOf(com.booksmith.common.error.ApiException.class);
    }

    @Test
    void layoutChangesThePageSizeLetterAndOrnament() throws Exception {
        var layout = new com.booksmith.manuscript.api.BookLayout("6x9", 12.0, "airy", "wide", "modern", "asterism");
        ExportBook book = book(null, null).withLayout(layout);
        String html = PdfWriter.html(book);
        assertThat(html).contains("size: 152.4mm 228.6mm; margin: 24mm 21mm 26mm 21mm;")
                .contains("font-size: 12pt; line-height: 1.6;")
                .contains("border-bottom: 0.5pt solid")
                .contains("<p class=\"scene-break\">⁂</p>").doesNotContain("* * *");

        try (PDDocument document = Loader.loadPDF(PdfWriter.write(book))) {
            var box = document.getPage(0).getMediaBox();
            // 6×9 polegadas = 432 × 648 pontos
            assertThat(box.getWidth()).isCloseTo(432f, org.assertj.core.data.Offset.offset(1f));
            assertThat(box.getHeight()).isCloseTo(648f, org.assertj.core.data.Offset.offset(1f));
        }
    }

    @Test
    void previewRendersTheRequestedPagesAsImages() {
        byte[] pdf = PdfWriter.write(book(null, null));
        var preview = PdfPreview.render(pdf, 2, 10);
        assertThat(preview.pageCount()).isEqualTo(4);
        // até 4 por pedido, e nunca além do fim do livro
        assertThat(preview.pages()).extracting(PdfPreview.Page::number).containsExactly(2, 3, 4);
        assertThat(preview.pages().getFirst().image()).startsWith("data:image/png;base64,");
        assertThat(PdfPreview.render(pdf, 9, 2).pages()).isEmpty();
    }

    @Test
    void emptyPagesAreLeftOut() {
        var pages = new com.booksmith.manuscript.api.Manuscripts.BookPages(" ", "\n", null, "Sem epígrafe", null, null, "Título");
        assertThat(pages).isEqualTo(com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY);
        assertThat(BookPagesHtml.front(pages)).isEmpty();
        assertThat(BookPagesHtml.back(pages, "Lia")).isEmpty();
    }

    @Test
    void pdfWithPartsRenders() {
        assertThat(PdfWriter.html(withParts())).contains("class=\"part-page\"").contains("toc-part");
        assertThat(PdfWriter.write(withParts())).isNotEmpty();
    }

    @Test
    void epubWithoutCoverIsValidToo(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("livro.epub");
        Files.write(file, EpubWriter.write(book(null, null)));

        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
    }

    @Test
    void theCoverFillsTheWholeFirstPageWithoutAWhiteBorder() throws Exception {
        var blue = new java.awt.image.BufferedImage(148, 210, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = blue.createGraphics();
        graphics.setColor(java.awt.Color.BLUE);
        graphics.fillRect(0, 0, 148, 210);
        graphics.dispose();
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(blue, "png", png);

        byte[] pdf = PdfWriter.write(book(new ImageData(png.toByteArray(), "image/png", "png"), null));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            var page = new org.apache.pdfbox.rendering.PDFRenderer(document).renderImageWithDPI(0, 72);
            // os quatro cantos de cima e o meio: azuis (antes, a margem do body deixava um filete branco em cima e
            // à esquerda e cortava a direita)
            for (int[] at : new int[][] {{1, 1}, {page.getWidth() - 2, 1}, {1, page.getHeight() / 2},
                    {page.getWidth() - 2, page.getHeight() / 2}}) {
                var color = new java.awt.Color(page.getRGB(at[0], at[1]));
                assertThat(color.getBlue()).as("pixel %s,%s", at[0], at[1]).isGreaterThan(200);
                assertThat(color.getRed()).as("pixel %s,%s", at[0], at[1]).isLessThan(60);
            }
        }
    }

    @Test
    void withACoverTheTextStillStaysInsideTheMargins() throws Exception {
        byte[] pdf = PdfWriter.write(book(new ImageData(PNG, "image/png", "png"), null));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            float pageWidth = document.getPage(0).getMediaBox().getWidth();
            float[] rightmost = {0};
            float[] leftmost = {pageWidth};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, java.util.List<org.apache.pdfbox.text.TextPosition> positions) {
                    for (var p : positions) {
                        rightmost[0] = Math.max(rightmost[0], p.getXDirAdj() + p.getWidthDirAdj());
                        leftmost[0] = Math.min(leftmost[0], p.getXDirAdj());
                    }
                }
            };
            // Da folha de rosto em diante (a página 1 é a capa)
            stripper.setStartPage(2);
            stripper.getText(document);
            float sideMargin = (float) (17 / 25.4 * 72);
            // Antes, com a capa sem margem, a largura do texto virava a da página inteira e o texto saía pela direita
            assertThat(rightmost[0]).as("texto mais à direita").isLessThanOrEqualTo(pageWidth - sideMargin + 2);
            assertThat(leftmost[0]).as("texto mais à esquerda").isGreaterThanOrEqualTo(sideMargin - 2);
        }
    }

    @Test
    void pdfHasTheTextTocAndBookmarks() throws Exception {
        byte[] pdf = PdfWriter.write(book(new ImageData(PNG, "image/png", "png"), null));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            // capa, folha de rosto, sumário e um capítulo por página
            assertThat(document.getNumberOfPages()).isEqualTo(5);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("A Conversão");
            assertThat(document.getDocumentInformation().getAuthor()).isEqualTo("Sérgio Holovati");

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            assertThat(text).contains("Lia entrou no café & sentou devagar — sem saber <nada>.");
            assertThat(text).containsPattern("\\*\\s+\\*\\s+\\*");
            // o sumário traz o número da página de cada capítulo
            assertThat(text).containsPattern("O Encontro[\\s.]+4");
            assertThat(text).containsPattern("Capítulo 2[\\s.]+5");

            List<String> bookmarks = new ArrayList<>();
            for (PDOutlineItem item : document.getDocumentCatalog().getDocumentOutline().children()) {
                bookmarks.add(item.getTitle());
            }
            assertThat(bookmarks).containsExactly("O Encontro", "Capítulo 2");
        }
    }

    @Test
    void fanficPdfStartsWithTheFanEditionNotice() throws Exception {
        byte[] pdf = PdfWriter.write(book(null, "Harry Potter"));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            assertThat(stripper.getText(document)).contains("Edição de fã", "baseada em Harry Potter");
        }
    }

    @Test
    void docxIsAWordManuscriptWithNativeStyles() throws Exception {
        byte[] docx = DocxWriter.write(book(null, null));

        assertThat(entries(docx)).contains("[Content_Types].xml", "word/document.xml", "word/styles.xml",
                "word/footer1.xml", "docProps/core.xml");
        String document = entry(docx, "word/document.xml");
        String styles = entry(docx, "word/styles.xml");
        // Todo XML do pacote tem de ser bem-formado, ou o Word recusa o arquivo
        for (String name : entries(docx)) {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(entry(docx, name).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        assertThat(document).contains("<w:pStyle w:val=\"Title\"/></w:pPr><w:r><w:t xml:space=\"preserve\">A Conversão</w:t>");
        assertThat(document).contains("<w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t xml:space=\"preserve\">O Encontro</w:t>");
        assertThat(document).contains("<w:ind w:firstLine=\"709\"/></w:pPr><w:r><w:t xml:space=\"preserve\">Lia entrou no café &amp; sentou </w:t></w:r>"
                + "<w:r><w:rPr><w:b/><w:i/></w:rPr><w:t xml:space=\"preserve\">devagar</w:t></w:r>");
        assertThat(document).contains("<w:jc w:val=\"center\"/></w:pPr><w:r><w:t xml:space=\"preserve\">* * *</w:t>");
        assertThat(document).contains("<w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:t xml:space=\"preserve\">Mais tarde</w:t>");
        assertThat(document).contains("<w:pStyle w:val=\"Quote\"/></w:pPr><w:r><w:t xml:space=\"preserve\">Querida,</w:t></w:r><w:r><w:br/></w:r>");
        assertThat(document).contains("<w:t xml:space=\"preserve\">3. </w:t></w:r><w:r><w:t xml:space=\"preserve\">pão</w:t>");
        // o corretor do Word usa o idioma do livro
        assertThat(styles).contains("<w:lang w:val=\"pt-BR\"/>");
        assertThat(entry(docx, "docProps/core.xml")).contains("<dc:creator>Sérgio Holovati</dc:creator>");
    }

    @Test
    void fanficDocxCarriesTheNoticeOnTheTitlePage() throws Exception {
        String document = entry(DocxWriter.write(book(null, "Harry Potter")), "word/document.xml");

        assertThat(document).contains("Obra de fã baseada em Harry Potter");
        assertThat(document.indexOf("Edição de fã")).isLessThan(document.indexOf("Heading1"));
    }

    private static String entry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Sem " + name);
    }

    private static ExportBook book(ImageData cover, String fandom) {
        String empty = "{\"type\":\"doc\",\"attrs\":{\"schemaVersion\":1},\"content\":[{\"type\":\"paragraph\","
                + "\"attrs\":{\"id\":\"x1\"},\"content\":[{\"type\":\"text\",\"text\":\"Fim.\"}]}]}";
        return new ExportBook(UUID.randomUUID(), "A Conversão", "Sérgio Holovati", "pt-BR", "Uma história de café.",
                fandom, cover, Instant.parse("2026-11-08T12:00:00.123Z"), List.of(
                new ExportBook.Chapter("O Encontro", JSON.readTree(CHAPTER_ONE)),
                new ExportBook.Chapter(BookHtml.chapterTitle("", 2), JSON.readTree(empty))));
    }

    private static List<String> entries(byte[] zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static com.booksmith.manuscript.api.BookLayout withFont(String trim, String font) {
        return new com.booksmith.manuscript.api.BookLayout(trim, null, null, null, null, null, null, null, null, null,
                null, null, font);
    }

    private static java.util.Set<String> pdfFonts(byte[] pdf) throws Exception {
        java.util.Set<String> names = new java.util.TreeSet<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (var page : document.getPages()) {
                for (var name : page.getResources().getFontNames()) {
                    names.add(page.getResources().getFont(name).getName());
                }
            }
        }
        return names;
    }

    @Test
    void theBookFontAndAPageFontGoIntoThePictureBookPdf() throws Exception {
        String text = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Era uma vez.\"}]}]}";
        var bookFont = new com.booksmith.manuscript.api.PageDesign(null, null, "bottom", null, null, null, null, null,
                null);
        var ownFont = new com.booksmith.manuscript.api.PageDesign(null, null, "top", null, null, null, null, null,
                null, null, null, null, null, null, null, "patrick-hand");
        var book = new ExportBook(UUID.randomUUID(), "Fontes", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-17T12:00:00Z"),
                List.of(new ExportBook.Chapter("Página 1", JSON.readTree(text), null, bookFont),
                        new ExportBook.Chapter("Página 2", JSON.readTree(text), null, ownFont)),
                java.util.Map.of(), com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY,
                withFont("20x20", "nunito"), true);

        String html = PictureBookHtml.html(book);
        assertThat(html).contains("body { margin: 0; font-family: 'Nunito';").contains("font-family: 'Patrick Hand';");
        assertThat(pdfFonts(PdfWriter.write(book))).anyMatch(name -> name.contains("Nunito"))
                .anyMatch(name -> name.contains("PatrickHand"));
    }

    @Test
    void theChosenFontIsTheTextOfTheNovelPdf() throws Exception {
        ExportBook book = book(null, null).withLayout(withFont("a5", "eb-garamond"));
        assertThat(pdfFonts(PdfWriter.write(book))).anyMatch(name -> name.contains("EBGaramond"));
        // Sem escolha, a Literata de sempre
        assertThat(pdfFonts(PdfWriter.write(book(null, null)))).allMatch(name -> name.contains("Literata"));
    }

    @Test
    void theChosenFontIsEmbeddedInTheEpubAndPassesEpubcheck(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(book(null, null).withLayout(withFont("a5", "andika")));
        Path file = dir.resolve("fonte.epub");
        Files.write(file, epub);
        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
        assertThat(entries(epub)).contains("EPUB/fonts/Andika-Regular.ttf", "EPUB/fonts/Andika-BoldItalic.ttf");
        assertThat(entry(epub, "EPUB/css/book.css")).contains("@font-face { font-family: 'Andika'")
                .contains("body { font-family: 'Andika', serif; }");
        // A padrão não vai embutida: o leitor de ePub usa a dele
        assertThat(entries(EpubWriter.write(book(null, null)))).noneMatch(name -> name.startsWith("EPUB/fonts/"));
    }

    @Test
    void wordUsesTheChosenFontOrTimesNewRoman() throws Exception {
        assertThat(entry(DocxWriter.write(book(null, null).withLayout(withFont("a5", "lora"))), "word/styles.xml"))
                .contains("<w:rFonts w:ascii=\"Lora\"");
        assertThat(entry(DocxWriter.write(book(null, null)), "word/styles.xml"))
                .contains("<w:rFonts w:ascii=\"Times New Roman\"");
    }

    @Test
    void hazeLightensTheArtBehindTheTextWithASoftEdge() throws Exception {
        var layout = new com.booksmith.manuscript.api.BookLayout("20x20", null, null, null, null, null, null, null,
                false, false, false);
        String text = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Oi.\"}]}]}";
        var page = new com.booksmith.manuscript.api.PageDesign(IMAGE_ID, null, "free", "center", "m", "dark", true,
                false, null, 20.0, 10.0, 60.0, null, null, null, null, true);
        assertThat(page.hasBand()).as("névoa e faixa: uma só").isFalse();
        var book = new ExportBook(UUID.randomUUID(), "Névoa", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-17T12:00:00Z"),
                List.of(new ExportBook.Chapter("Página 1", PictureBookHtml.withBackground(JSON.readTree(text), page),
                        null, page)),
                java.util.Map.of(IMAGE_ID, new ImageData(twoColors(2000, 2000), "image/png", "png")),
                com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY, layout, true);
        assertThat(PictureBookHtml.html(book)).contains("haze-light").contains("background-image: url(data:image/png");

        try (PDDocument document = Loader.loadPDF(PdfWriter.write(book))) {
            var image = new org.apache.pdfbox.rendering.PDFRenderer(document).renderImageWithDPI(0, 72);
            double px = 72 / 25.4;
            // Dentro da caixa (40 a 160 mm, a partir de 20 mm), longe da palavra: o vermelho fica claro
            int inside = image.getRGB((int) (70 * px), (int) (30 * px));
            // Longe do texto, a arte como é
            int outside = image.getRGB((int) (70 * px), (int) (150 * px));
            assertThat((inside >> 8) & 0xff).as("verde perto do texto").isGreaterThan(150);
            assertThat((outside >> 8) & 0xff).as("verde longe do texto").isLessThan(40);
        }
        // A borda da névoa é transparente; o miolo, cheio
        assertThat(Haze.alpha(0.5, 0.5, 0.86)).isEqualTo(0.86);
        assertThat(Haze.alpha(0.0, 0.5, 0.86)).isZero();
        assertThat(Haze.alpha(0.5, 0.0, 0.86)).isZero();
    }

    @Test
    void blurSoftensTheArtBehindTheTextOnly() throws Exception {
        var layout = new com.booksmith.manuscript.api.BookLayout("20x20", null, null, null, null, null, null, null,
                false, false, false);
        String text = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Que não consigo pensar em mais nada\"}]}]}";
        // A caixa no meio da página, em cima da divisa entre o vermelho e o azul (100 mm)
        var page = new com.booksmith.manuscript.api.PageDesign(IMAGE_ID, null, "free", "center", "m", "dark", true,
                false, null, 25.0, 40.0, 50.0, null, null, null, null, true, true);
        assertThat(page.hasBand()).isFalse();
        assertThat(page.hasHaze()).as("desfoque, névoa e faixa: um só").isFalse();
        var book = new ExportBook(UUID.randomUUID(), "Desfoque", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-17T12:00:00Z"),
                List.of(new ExportBook.Chapter("Página 1", PictureBookHtml.withBackground(JSON.readTree(text), page),
                        null, page)),
                java.util.Map.of(IMAGE_ID, new ImageData(twoColors(2000, 2000), "image/png", "png")),
                com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY, layout, true);
        assertThat(PictureBookHtml.html(book)).contains("class=\"pb-blur\"");

        try (PDDocument document = Loader.loadPDF(PdfWriter.write(book))) {
            var image = new org.apache.pdfbox.rendering.PDFRenderer(document).renderImageWithDPI(0, 72);
            double px = 72 / 25.4;
            // Na divisa, atrás do texto (acima da linha): vermelho e azul misturados
            int behind = image.getRGB((int) (99 * px), (int) (78 * px));
            assertThat((behind >> 16) & 0xff).as("vermelho atrás do texto").isGreaterThan(60);
            assertThat(behind & 0xff).as("azul atrás do texto").isGreaterThan(60);
            // Na divisa, longe do texto: nítida (vermelho puro)
            int far = image.getRGB((int) (98 * px), (int) (170 * px));
            assertThat(far & 0xff).as("azul longe do texto").isLessThan(30);
        }
        assertThat(BlurPatch.ramp(0)).isZero();
        assertThat(BlurPatch.ramp(1)).isEqualTo(1.0);
        // Estimativa da altura: 30 letras, 15 por linha em 60 mm a 20 pt: 2 linhas com entrelinha 1,3
        assertThat(BlurPatch.textHeightMm("<p>" + "a".repeat(30) + "</p>", 60, 20))
                .isCloseTo(2 * 1.3 * 20 * 25.4 / 72, org.assertj.core.data.Offset.offset(0.01));
    }

    private static final String TWO_BLOCKS = "{\"type\":\"doc\",\"content\":["
            + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Primeiro bloco.\"}]},"
            + "{\"type\":\"sceneBreak\"},"
            + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Segundo bloco.\"}]},"
            + "{\"type\":\"sceneBreak\"},"
            + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Sobra.\"}]}]}";

    @Test
    void eachTextBlockGoesWhereTheAuthorPutIt() throws Exception {
        var layout = new com.booksmith.manuscript.api.BookLayout("20x20", null, null, null, null, null, null, null,
                false, false, false);
        var second = new com.booksmith.manuscript.api.TextBox(10.0, 70.0, 50.0, "s", "left", "light", "nunito", null,
                true, null, 3.0);
        var page = new com.booksmith.manuscript.api.PageDesign(null, "#336699", "top", "center", "l", "dark", null,
                false, null, null, null, null, null, null, null, null, null, null, null, List.of(second));
        var book = new ExportBook(UUID.randomUUID(), "Blocos", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-17T12:00:00Z"),
                List.of(new ExportBook.Chapter("Página 1", JSON.readTree(TWO_BLOCKS), null, page)),
                java.util.Map.of(), com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY, layout, true);

        String html = PictureBookHtml.html(book);
        assertThat(html.split("class=\"pb-text ", -1)).as("dois blocos").hasSize(3);
        // O segundo bloco: livre, a 70% da altura (140 mm), letra P, fonte e névoa próprias, 3 vezes mais alta
        assertThat(html).contains("top: 140.0mm").contains("font-family: 'Nunito'").contains("haze-dark")
                .contains("padding-top: 27.0mm").contains("margin-top: -18.0mm");
        // A sobra (mais quebras do que blocos) vai junto no último; a quebra não vira "* * *"
        assertThat(html).contains("Segundo bloco.").contains("Sobra.").doesNotContain("* * *");
        assertThat(html.indexOf("Sobra.")).isGreaterThan(html.indexOf("Segundo bloco."));
        assertThat(PdfWriter.write(book)).isNotEmpty();

        // No ePub e no Word, os blocos um depois do outro, sem a quebra
        String xhtml = book.chapters().getFirst().html();
        assertThat(xhtml).contains("Primeiro bloco.").contains("Segundo bloco.").doesNotContain("scene-break");
        assertThat(entry(DocxWriter.write(book), "word/document.xml")).doesNotContain("* * *");
    }

    @Test
    void textBoxesAreCheckedLikeThePage() {
        assertThatThrownBy(() -> new com.booksmith.manuscript.api.TextBox(10.0, 10.0, 50.0, "huge", null, null, null,
                null, null, null, null))
                .isInstanceOfSatisfying(com.booksmith.common.error.ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("page_invalid"));
        var box = new com.booksmith.manuscript.api.TextBox(null, null, null, null, null, null, null, true, true, true,
                9.0);
        assertThat(box.blur()).isTrue();
        assertThat(box.haze()).isFalse();
        assertThat(box.band()).isFalse();
        assertThat(box.backdropHeight()).isEqualTo(4.0);
        var many = java.util.Collections.nCopies(com.booksmith.manuscript.api.PageDesign.MAX_BOXES + 1, box);
        assertThatThrownBy(() -> new com.booksmith.manuscript.api.PageDesign(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, many))
                .isInstanceOfSatisfying(com.booksmith.common.error.ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("page_invalid"));
    }

    @Test
    void theArtGoesToTheBottomEdgeWithoutAWhiteStrip() throws Exception {
        var layout = new com.booksmith.manuscript.api.BookLayout("20x20", null, null, null, null, null, null, null,
                false, false, false);
        String text = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Fim.\"}]}]}";
        var page = new com.booksmith.manuscript.api.PageDesign(IMAGE_ID, null, "top", null, null, null, null, null,
                null);
        var book = new ExportBook(UUID.randomUUID(), "Borda", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-17T12:00:00Z"),
                List.of(new ExportBook.Chapter("Página 1", PictureBookHtml.withBackground(JSON.readTree(text), page), null, page),
                        new ExportBook.Chapter("Página 2", PictureBookHtml.withBackground(JSON.readTree(text), page), null, page)),
                java.util.Map.of(IMAGE_ID, new ImageData(twoColors(2000, 2000), "image/png", "png")),
                com.booksmith.manuscript.api.Manuscripts.BookPages.EMPTY, layout, true);
        try (PDDocument document = Loader.loadPDF(PdfWriter.write(book))) {
            assertThat(document.getNumberOfPages()).as("sem página em branco a mais").isEqualTo(2);
            var image = new org.apache.pdfbox.rendering.PDFRenderer(document).renderImageWithDPI(0, 150);
            int bottom = image.getRGB(image.getWidth() / 4, image.getHeight() - 1);
            assertThat((bottom >> 16) & 0xff).as("vermelho no pé").isGreaterThan(200);
            assertThat((bottom >> 8) & 0xff).as("sem branco no pé").isLessThan(60);
        }
    }

    @Test
    void pictureBookEbookIsFixedLayoutWithTheTextOverTheArt(@TempDir Path dir) throws Exception {
        byte[] epub = EpubWriter.write(pictureBook());
        Path file = dir.resolve("infantil.epub");
        Files.write(file, epub);
        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();

        String opf = entry(epub, "EPUB/package.opf");
        assertThat(opf).contains("<meta property=\"rendition:layout\">pre-paginated</meta>")
                .contains("properties=\"page-spread-left\"").contains("properties=\"page-spread-right\"");
        // A página: do tamanho do livro (20 cm = 756 px), com a arte e o texto por cima, na mesma página
        String page = entry(epub, "EPUB/page-001.xhtml");
        assertThat(page).contains("<meta name=\"viewport\" content=\"width=756, height=")
                .contains("class=\"pb-art\"").contains("src=\"images/ill-").contains("class=\"pb-text")
                .contains("Página 1 da história.");
        assertThat(page.indexOf("pb-text")).isGreaterThan(page.indexOf("pb-art"));
        // Sem sangria e sem número de página no e-book
        assertThat(entry(epub, "EPUB/css/book.css")).doesNotContain("@page");
        assertThat(page).doesNotContain("pb-number");
    }

    @Test
    void printerSheetsJoinTheOpenBookLikeTheMiolo() throws Exception {
        ExportBook book = pictureBook();
        var l = book.layout();
        var joined = new com.booksmith.manuscript.api.BookLayout(l.trim(), l.fontSize(), l.leading(), l.margins(),
                l.chapterStyle(), l.sceneBreak(), l.customWidthMm(), l.customHeightMm(), l.bleed(), l.autoPages(),
                l.pageNumbers(), l.fixedLayout(), l.font(), null, "#ffeecc", true);
        int pages;
        try (PDDocument single = Loader.loadPDF(PdfWriter.write(book))) {
            pages = single.getNumberOfPages();
        }
        try (PDDocument sheets = Loader.loadPDF(PdfWriter.write(book.withLayout(joined)))) {
            // A primeira e a última sozinhas, as outras em pares (o livro aberto)
            assertThat(sheets.getNumberOfPages()).isEqualTo(pages / 2 + 1);
            float pt = 72 / 25.4f;
            var first = sheets.getPage(0);
            var pair = sheets.getPage(1);
            assertThat(pair.getMediaBox().getWidth()).isCloseTo(2 * first.getMediaBox().getWidth(),
                    org.assertj.core.data.Offset.offset(0.5f));
            // O corte da folha vai de uma borda de corte à outra: 2 × 200 mm, com a sangria (3,2 mm) nas bordas de fora
            assertThat(pair.getTrimBox().getWidth() / pt).isCloseTo(400f, org.assertj.core.data.Offset.offset(0.5f));
            assertThat(pair.getTrimBox().getLowerLeftX() / pt).isCloseTo(3.2f, org.assertj.core.data.Offset.offset(0.2f));
            assertThat(sheets.getPage(sheets.getNumberOfPages() - 1).getMediaBox().getWidth())
                    .isCloseTo(first.getMediaBox().getWidth(), org.assertj.core.data.Offset.offset(0.5f));
        }
    }

    @Test
    void printChecklistSaysWhatThePictureBookLacks() throws Exception {
        var check = PrintCheck.check(pictureBook());
        var status = check.pages().stream().collect(java.util.stream.Collectors.toMap(PrintCheck.PageCheck::key,
                PrintCheck.PageCheck::status));
        assertThat(status).containsEntry("credits", "warn").containsEntry("catalogCard", "warn")
                .containsEntry("isbn", "warn").containsEntry("titlePage", "ok").containsEntry("aboutAuthor", "warn")
                .containsEntry("bleed", "ok").containsEntry("pageCount", "ok");
        assertThat(check.pageCount() % 4).isZero();
        try (PDDocument pdf = Loader.loadPDF(PdfWriter.write(pictureBook()))) {
            assertThat(check.pageCount()).as("a conta bate com o PDF").isEqualTo(pdf.getNumberOfPages());
        }
        // A prévia do e-book: sem as páginas em branco do fim
        try (PDDocument ebook = Loader.loadPDF(PdfWriter.writeEbookPreview(pictureBook()))) {
            assertThat(ebook.getNumberOfPages()).isLessThanOrEqualTo(check.pageCount());
        }
    }

    /** Livro de texto com dois capítulos (com id) e páginas de ilustração no começo, depois do 1º e no fim. */
    private static ExportBook withArtPages(String... places) throws Exception {
        List<ExportBook.Chapter> chapters = new ArrayList<>();
        for (String title : List.of("Um", "Dois")) {
            chapters.add(new ExportBook.Chapter(title, JSON.readTree(
                    "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Texto.\"}]}]}"),
                    null, null, UUID.randomUUID()));
        }
        List<com.booksmith.manuscript.api.ExtraPage> arts = new ArrayList<>();
        for (int i = 0; i < places.length; i++) {
            String place = "chapter1".equals(places[i]) ? chapters.getFirst().id().toString() : places[i];
            arts.add(new com.booksmith.manuscript.api.ExtraPage("illustration", place, IMAGE_ID, null,
                    "00000000-0000-0000-0000-00000000000" + (i + 1), i % 2 == 1));
        }
        var pages = new com.booksmith.manuscript.api.Manuscripts.BookPages(null, "Para a Lia.", null, null, null, null,
                null).withExtras(arts);
        return new ExportBook(UUID.randomUUID(), "Romance", "Autora", "pt-BR", null, null, null,
                Instant.parse("2026-11-23T12:00:00Z"), chapters,
                java.util.Map.of(IMAGE_ID, new ImageData(twoColors(1200, 1600), "image/png", "png")), pages);
    }

    @Test
    void artPagesOfATextBookGoWhereTheAuthorPutThem() throws Exception {
        ExportBook book = withArtPages("start", "chapter1", "end", "front");
        String html = PdfWriter.html(book);
        int start = html.indexOf("id=\"art-00000000-0000-0000-0000-000000000001\"");
        int afterOne = html.indexOf("id=\"art-00000000-0000-0000-0000-000000000002\"");
        int end = html.indexOf("id=\"art-00000000-0000-0000-0000-000000000003\"");
        int front = html.indexOf("id=\"art-00000000-0000-0000-0000-000000000004\"");
        // No começo antes da folha de rosto; antes do 1º capítulo depois do sumário; depois do 1º; no fim
        assertThat(start).isPositive().isLessThan(html.indexOf("id=\"title-page\""));
        assertThat(front).isGreaterThan(html.indexOf("id=\"toc\"")).isLessThan(html.indexOf("id=\"chapter-1\""));
        assertThat(afterOne).isGreaterThan(html.indexOf("id=\"chapter-1\"")).isLessThan(html.indexOf("id=\"chapter-2\""));
        assertThat(end).isGreaterThan(html.indexOf("id=\"chapter-2\""));
        // A segunda vai até a borda; a primeira inteira dentro das margens
        assertThat(html).contains("<div id=\"art-00000000-0000-0000-0000-000000000001\" class=\"illustration-page\"")
                .contains("<div id=\"art-00000000-0000-0000-0000-000000000002\" class=\"bleed-page\"");

        // O ePub: uma página para cada, no mesmo lugar da leitura
        byte[] epub = EpubWriter.write(book);
        String opf = entry(epub, "EPUB/package.opf");
        assertThat(opf.indexOf("idref=\"art-00000000-0000-0000-0000-000000000001\"")).isLessThan(opf.indexOf("idref=\"title\""));
        assertThat(opf.indexOf("idref=\"art-00000000-0000-0000-0000-000000000002\""))
                .isGreaterThan(opf.indexOf("idref=\"chapter-1\"")).isLessThan(opf.indexOf("idref=\"chapter-2\""));
        assertThat(entry(epub, "EPUB/art-00000000-0000-0000-0000-000000000003.xhtml")).contains("<img src=\"images/ill-");
    }

    @Test
    void thePageMapSaysWhereEachPartOfATextBookStarts() throws Exception {
        ExportBook book = withArtPages("chapter1");
        List<PdfWriter.Block> blocks = new ArrayList<>();
        byte[] pdf = PdfWriter.write(book, blocks);
        PdfPreview.PageMap map = PdfPreview.map(pdf, blocks);
        assertThat(map.blocks()).extracting(PdfPreview.Block::kind)
                .containsExactly("title", "front", "toc", "chapter", "illustration", "chapter");
        assertThat(map.blocks()).extracting(PdfPreview.Block::firstPage).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(map.blocks().get(3).ref()).isEqualTo(book.chapters().getFirst().id().toString());
        assertThat(map.blocks().stream().mapToInt(PdfPreview.Block::pageCount).sum()).isEqualTo(map.pageCount());
        // A paginação é a mesma do PDF de verdade
        try (PDDocument real = Loader.loadPDF(PdfWriter.write(book))) {
            assertThat(real.getNumberOfPages()).isEqualTo(map.pageCount());
        }
        // As miniaturas: pequenas, em JPEG
        PdfPreview.Preview thumbs = PdfPreview.render(pdf, 1, 30, true);
        assertThat(thumbs.pages()).hasSize(map.pageCount());
        assertThat(thumbs.pages().getFirst().image()).startsWith("data:image/jpeg;base64,");
    }

    @Test
    void generatedPagesGoWhereTheAuthorPutThemWithTheirOwnBackground() throws Exception {
        ExportBook base = pictureBook();
        // As páginas da história com id, para a dedicatória ficar depois da primeira
        List<ExportBook.Chapter> chapters = new ArrayList<>();
        for (var c : base.chapters()) {
            chapters.add(new ExportBook.Chapter(c.title(), c.document(), c.partTitle(), c.page(), UUID.randomUUID()));
        }
        String first = chapters.getFirst().id().toString();
        var pages = new com.booksmith.manuscript.api.Manuscripts.BookPages(null, "Para a Lia.", null, null, null, null,
                null, false, null, "Composto em Andika.", null).withExtras(List.of(
                new com.booksmith.manuscript.api.ExtraPage("dedication", first, null, "#123456"),
                new com.booksmith.manuscript.api.ExtraPage("credits", "end", IMAGE_ID, null)));
        ExportBook book = new ExportBook(base.id(), base.title(), base.author(), base.language(), null, null, null,
                base.generatedAt(), chapters, base.illustrations(), pages, base.layout(), true);

        var parts = PictureBookHtml.sequence(book, PdfWriter.pageBoxMm(book.layout()), id -> "x", true).parts();
        List<String> titles = parts.stream().map(PictureBookHtml.Part::title).toList();
        // Folha de rosto e página de rosto no começo; a dedicatória depois da página 1; os créditos no fim, antes das
        // páginas em branco e do colofão, que fecha o livro
        assertThat(titles.subList(0, 4)).containsExactly("Folha de rosto", "Página de rosto", "Página 1", "Dedicatória");
        assertThat(titles.indexOf("Créditos")).isGreaterThan(titles.indexOf("Página 3"));
        assertThat(titles.getLast()).isEqualTo("Colofão");
        // O fundo próprio: a cor da dedicatória, a ilustração nos créditos
        assertThat(parts.get(3).html()).contains("background-color: #123456").doesNotContain("pb-art");
        assertThat(parts.get(titles.indexOf("Créditos")).html()).contains("class=\"pb-art\"");
        // A conta das páginas bate com o PDF e fecha em múltiplo de 4
        int count = PictureBookHtml.pageCount(book);
        assertThat(count % 4).isZero();
        try (PDDocument pdf = Loader.loadPDF(PdfWriter.write(book))) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(count);
        }
    }

    @Test
    void generatedPagesAfterAPageThatLeftTheBookGoBackToTheirPlace() {
        var stored = List.of(new com.booksmith.manuscript.api.ExtraPage("colophon", "start", null, null),
                new com.booksmith.manuscript.api.ExtraPage("dedication", UUID.randomUUID().toString(), null, null));
        var arranged = com.booksmith.manuscript.api.ExtraPage.arrange(stored,
                List.of("cover", "credits", "title", "dedication", "colophon"), List.of());
        assertThat(arranged).extracting(com.booksmith.manuscript.api.ExtraPage::key)
                .containsExactly("cover", "credits", "title", "colophon", "dedication");
        assertThat(arranged).extracting(com.booksmith.manuscript.api.ExtraPage::after)
                .containsExactly("start", "start", "start", "start", "start");
    }
}
