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

    @Test
    void epubWithoutCoverIsValidToo(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("livro.epub");
        Files.write(file, EpubWriter.write(book(null, null)));

        StringWriter messages = new StringWriter();
        assertThat(new EpubCheck(file.toFile(), new PrintWriter(messages)).doValidate())
                .withFailMessage(messages::toString).isZero();
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
