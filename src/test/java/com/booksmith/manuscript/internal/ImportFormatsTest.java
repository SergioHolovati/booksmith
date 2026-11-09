package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.internal.ImportedText.Paragraph;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportFormatsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ===== .txt =====

    @Test
    void plainTextIsOneParagraphPerLineAndSplitsOnChapterTitles() {
        String text = """
                Capítulo 1
                \tLia entrou no café.
                — Oi.
                — Oi.

                * * *
                Mais tarde, choveu.
                Capítulo 2
                Ele sorriu.
                """;

        var plan = plan(text.getBytes(StandardCharsets.UTF_8), "rascunho.txt");

        assertThat(plan.rule()).isEqualTo(ChapterSplitter.SplitRule.CHAPTER_TITLES);
        assertThat(plan.chapters()).extracting(ChapterSplitter.Draft::title).containsExactly("Capítulo 1", "Capítulo 2");
        String first = plan.chapters().getFirst().document().json();
        assertThat(first).contains("\"indent\":true").contains("\"text\":\"Lia entrou no café.\"");
        // diálogos curtos, um por linha, não são juntados
        assertThat(first).contains("\"text\":\"— Oi.\"").doesNotContain("— Oi. — Oi.");
        assertThat(first).contains("\"type\":\"sceneBreak\"");
    }

    @Test
    void hardWrappedTextJoinsTheLinesOfEachParagraph() {
        String text = """
                Lia entrou no café sem saber que ele estaria lá, sentado
                perto da janela, com o mesmo casaco de sempre e um livro
                aberto que ele não lia.

                Ela pensou em voltar. Pensou em fingir que não o tinha
                visto, em pedir o café para viagem e sair sem olhar.
                """;

        List<Paragraph> paragraphs = TextReader.plain(text).paragraphs();

        assertThat(paragraphs).hasSize(2);
        assertThat(paragraphs.getFirst().text()).isEqualTo("Lia entrou no café sem saber que ele estaria lá, sentado "
                + "perto da janela, com o mesmo casaco de sempre e um livro aberto que ele não lia.");
    }

    @Test
    void decodesUtf8WithBomAndOldWindowsText() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'O', 'l', (byte) 0xC3, (byte) 0xA1};
        assertThat(TextReader.decode(bom)).isEqualTo("Olá");
        assertThat(TextReader.decode("Coração\r\nAção".getBytes(Charset.forName("windows-1252"))))
                .isEqualTo("Coração\nAção");
    }

    // ===== .md =====

    @Test
    void markdownKeepsHeadingsFormattingAndSceneBreaks() {
        String markdown = """
                # A Conversão

                ## O Encontro

                \tLia entrou no café *sem saber* que **ele** estaria lá.
                Ninguém ~~viu~~ <u>notou</u>.

                #

                > Querida, volte logo.

                - pão
                - leite

                ## A Partida

                Fim.
                """;

        var plan = plan(markdown.getBytes(StandardCharsets.UTF_8), "livro.md");

        assertThat(plan.rule()).isEqualTo(ChapterSplitter.SplitRule.HEADINGS);
        assertThat(plan.documentTitle()).isEqualTo("A Conversão");
        assertThat(plan.chapters()).extracting(ChapterSplitter.Draft::title).containsExactly("O Encontro", "A Partida");
        String first = plan.chapters().getFirst().document().json();
        // Tab no começo não vira bloco de código: é o recuo do parágrafo
        assertThat(first).contains("\"indent\":true");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\"sem saber\",\"marks\":[{\"type\":\"italic\"}]}");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\"ele\",\"marks\":[{\"type\":\"bold\"}]}");
        assertThat(first).contains("estaria lá. Ninguém ");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\"viu\",\"marks\":[{\"type\":\"strike\"}]}");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\"notou\",\"marks\":[{\"type\":\"underline\"}]}");
        assertThat(first).contains("\"type\":\"sceneBreak\"");
        assertThat(first).contains("\"text\":\"Querida, volte logo.\"").contains("\"text\":\"• pão\"");
    }

    @Test
    void markdownWithSeveralTopHeadingsUsesThemAsChapters() {
        var plan = plan("# Um\n\nTexto.\n\n# Dois\n\nMais.\n".getBytes(StandardCharsets.UTF_8), "notas.markdown");

        assertThat(plan.documentTitle()).isNull();
        assertThat(plan.chapters()).extracting(ChapterSplitter.Draft::title).containsExactly("Um", "Dois");
    }

    // ===== .odt =====

    private static final String ODT_STYLES = """
            <office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
                xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
              <office:styles>
                <style:style style:name="Standard" style:family="paragraph"/>
                <style:style style:name="Title" style:family="paragraph" style:parent-style-name="Standard"/>
                <style:style style:name="Text_20_body" style:display-name="Text body" style:family="paragraph">
                  <style:paragraph-properties fo:text-indent="1.25cm"/>
                </style:style>
                <style:style style:name="Emphasis" style:family="text">
                  <style:text-properties fo:font-style="italic"/>
                </style:style>
              </office:styles>
            </office:document-styles>
            """;

    private static final String ODT_CONTENT = """
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
                xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
              <office:automatic-styles>
                <style:style style:name="T1" style:family="text">
                  <style:text-properties fo:font-weight="bold"/>
                </style:style>
                <style:style style:name="P1" style:family="paragraph" style:parent-style-name="Text_20_body">
                  <style:text-properties style:text-underline-style="solid"/>
                </style:style>
              </office:automatic-styles>
              <office:body><office:text>
                <text:tracked-changes><text:changed-region><text:deletion><text:p>Apagado</text:p></text:deletion></text:changed-region></text:tracked-changes>
                <text:p text:style-name="Title">A Conversão</text:p>
                <text:h text:outline-level="1">O Encontro</text:h>
                <text:p text:style-name="Text_20_body">Lia entrou<text:s text:c="2"/>no <text:span text:style-name="Emphasis">café</text:span> <text:span text:style-name="T1">sem</text:span>
                   saber.<text:note><text:note-citation>1</text:note-citation><text:note-body><text:p>Nota</text:p></text:note-body></text:note></text:p>
                <text:p text:style-name="P1">Tudo sublinhado<text:line-break/>em duas linhas.</text:p>
                <text:p><draw:frame><draw:image/></draw:frame></text:p>
                <text:p>* * *</text:p>
                <text:p>Ele chegou.</text:p>
                <text:h text:outline-level="1">A Partida</text:h>
                <text:list><text:list-item><text:p>Fim.</text:p></text:list-item></text:list>
              </office:text></office:body>
            </office:document-content>
            """;

    @Test
    void odtKeepsHeadingsStylesAndIndent() {
        var plan = plan(odt("application/vnd.oasis.opendocument.text", ODT_CONTENT), "livro.odt");

        assertThat(plan.rule()).isEqualTo(ChapterSplitter.SplitRule.HEADINGS);
        assertThat(plan.documentTitle()).isEqualTo("A Conversão");
        assertThat(plan.chapters()).extracting(ChapterSplitter.Draft::title).containsExactly("O Encontro", "A Partida");
        String first = plan.chapters().getFirst().document().json();
        assertThat(first).doesNotContain("Apagado").doesNotContain("Nota");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\"Lia entrou  no \"}");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\"café\",\"marks\":[{\"type\":\"italic\"}]}");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\"sem\",\"marks\":[{\"type\":\"bold\"}]}");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\" saber.\"}");
        assertThat(first).contains("\"indent\":true");
        assertThat(first).contains("\"text\":\"Tudo sublinhado\",\"marks\":[{\"type\":\"underline\"}]},{\"type\":\"hardBreak\"}");
        assertThat(first).contains("\"type\":\"sceneBreak\"");
        assertThat(plan.warnings()).anyMatch(w -> w.contains("1 imagem")).anyMatch(w -> w.contains("1 nota de rodapé"));
    }

    // ===== Reconhecimento do formato =====

    @Test
    void formatComesFromTheContent() {
        assertThat(ImportFormat.detect(odt("application/vnd.oasis.opendocument.text", ODT_CONTENT), "livro.docx"))
                .isEqualTo(ImportFormat.ODT);
        assertThat(ImportFormat.detect("texto".getBytes(StandardCharsets.UTF_8), null)).isEqualTo(ImportFormat.TEXT);
        assertThat(ImportFormat.detect("# Um".getBytes(StandardCharsets.UTF_8), "Um.MD")).isEqualTo(ImportFormat.MARKDOWN);
    }

    @Test
    void unsupportedFilesExplainWhatToDo() {
        assertRejected(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0}, "velho.doc",
                "import_unsupported_format", ".doc");
        assertRejected("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), "livro.pdf", "import_unsupported_format", "PDF");
        assertRejected("{\\rtf1 oi}".getBytes(StandardCharsets.US_ASCII), "livro.rtf", "import_unsupported_format", ".rtf");
        assertRejected(odt("application/vnd.oasis.opendocument.spreadsheet", ODT_CONTENT), "contas.ods",
                "import_unsupported_format", "planilha");
        assertRejected("isto não é um zip".getBytes(StandardCharsets.UTF_8), "livro.docx", "docx_invalid", ".docx");
        assertRejected("isto não é um zip".getBytes(StandardCharsets.UTF_8), "livro.odt", "odt_invalid", ".odt");
        assertRejected("texto".getBytes(StandardCharsets.UTF_8), "foto.jpg", "import_unsupported_format", ".md");
        assertRejected(new byte[]{'G', 'I', 'F', 0, 1}, null, "import_unsupported_format", ".txt");
    }

    private static void assertRejected(byte[] file, String name, String code, String hint) {
        assertThatThrownBy(() -> ImportFormat.read(file, name))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(code);
                    assertThat(e.getMessage()).contains(hint);
                });
    }

    private ChapterSplitter.Plan plan(byte[] file, String name) {
        return ChapterSplitter.plan(ImportFormat.read(file, name), ChapterSplitter.Mode.AUTO, name, mapper);
    }

    private static byte[] odt(String mimetype, String content) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "mimetype", mimetype);
            put(zip, "styles.xml", ODT_STYLES);
            put(zip, "content.xml", content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
