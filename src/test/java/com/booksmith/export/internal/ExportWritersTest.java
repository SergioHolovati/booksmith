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
        assertThat(PdfWriter.dataUri(illustrated("image/png"), IMAGE_ID)).startsWith("data:image/png;base64,");
        assertThat(PdfWriter.dataUri(illustrated("image/webp"), IMAGE_ID)).isNull();
        assertThat(PdfWriter.write(illustrated("image/png"))).isNotEmpty();
    }

    @Test
    void wordManuscriptGetsAPlaceholder() throws Exception {
        String document = entry(DocxWriter.write(illustrated("image/png")), "word/document.xml");
        assertThat(document).contains("[Ilustração: O mapa de Vel]").contains("[Ilustração]");
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
}
