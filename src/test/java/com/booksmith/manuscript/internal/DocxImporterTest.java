package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocxImporterTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** Estilos como o Word em português grava: o id é traduzido ("Ttulo1"), o nome interno não. */
    private static final String STYLES = """
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
              <w:style w:type="paragraph" w:styleId="Ttulo"><w:name w:val="Title"/></w:style>
              <w:style w:type="paragraph" w:styleId="Ttulo1"><w:name w:val="heading 1"/></w:style>
              <w:style w:type="paragraph" w:styleId="Ttulo2"><w:name w:val="heading 2"/></w:style>
              <w:style w:type="character" w:styleId="nfase"><w:name w:val="Emphasis"/><w:rPr><w:i/></w:rPr></w:style>
            </w:styles>
            """;

    @Test
    void splitsOnWordHeadingsAndKeepsFormatting() {
        byte[] docx = docx(STYLES,
                p("Ttulo", run("A Conversão")),
                p("Ttulo1", run("Capítulo 1 — O café")),
                "<w:p><w:pPr><w:ind w:firstLine=\"708\"/></w:pPr>" + run("Lia entrou ") + italic("sem saber") + run(".") + "</w:p>",
                p(null, run("* * *")),
                p(null, "<w:r><w:tab/><w:t>Recuo feito com Tab.</w:t></w:r>"),
                p("Ttulo2", run("Mais tarde")),
                p(null, "<w:r><w:rPr><w:rStyle w:val=\"nfase\"/></w:rPr><w:t>Ênfase por estilo</w:t></w:r>"),
                p("Ttulo1", run("Capítulo 2")),
                p(null, run("Ele sorriu.")));

        var plan = plan(docx, ChapterSplitter.Mode.AUTO);

        assertThat(plan.rule()).isEqualTo(ChapterSplitter.SplitRule.HEADINGS);
        assertThat(plan.documentTitle()).isEqualTo("A Conversão");
        assertThat(plan.chapters()).extracting(ChapterSplitter.Draft::title)
                .containsExactly("Capítulo 1 — O café", "Capítulo 2");

        String first = plan.chapters().getFirst().document().json();
        assertThat(first).contains("\"type\":\"sceneBreak\"");
        assertThat(first).contains("\"indent\":true");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\"sem saber\",\"marks\":[{\"type\":\"italic\"}]}");
        assertThat(first).contains("\"text\":\"Recuo feito com Tab.\"").doesNotContain("\\t");
        assertThat(first).contains("\"type\":\"heading\",\"attrs\":{\"id\"").contains("\"level\":2");
        assertThat(first).contains("{\"type\":\"text\",\"text\":\"Ênfase por estilo\",\"marks\":[{\"type\":\"italic\"}]}");
        assertThat(plan.chapters().getFirst().excerpt()).startsWith("Lia entrou sem saber.");
        assertThat(plan.chapters().get(1).document().wordCount()).isEqualTo(2);
    }

    @Test
    void splitsOnHandwrittenChapterTitlesWhenThereAreNoStyles() {
        byte[] docx = docx(null,
                p(null, run("Para minha avó.")),
                p(null, "<w:r><w:rPr><w:b/></w:rPr><w:t>CAPÍTULO UM</w:t></w:r>"),
                p(null, run("Era uma vez.")),
                p(null, run("Capítulo 2: a volta")),
                p(null, run("Fim.")),
                p(null, run("Epílogo")));

        var plan = plan(docx, ChapterSplitter.Mode.AUTO);

        assertThat(plan.rule()).isEqualTo(ChapterSplitter.SplitRule.CHAPTER_TITLES);
        assertThat(plan.chapters()).extracting(ChapterSplitter.Draft::title)
                .containsExactly("Abertura", "CAPÍTULO UM", "Capítulo 2: a volta", "Epílogo");
    }

    @Test
    void singleModeKeepsEverythingInOneChapterNamedAfterTheFile() {
        byte[] docx = docx(STYLES,
                p("Ttulo1", run("Capítulo 1")), p(null, run("Um.")),
                p("Ttulo1", run("Capítulo 2")), p(null, run("Dois.")));

        var plan = ChapterSplitter.plan(DocxReader.read(docx), ChapterSplitter.Mode.SINGLE, "meu_rascunho.docx", mapper);

        assertThat(plan.rule()).isEqualTo(ChapterSplitter.SplitRule.SINGLE);
        assertThat(plan.chapters()).singleElement().satisfies(chapter -> {
            assertThat(chapter.title()).isEqualTo("meu rascunho");
            assertThat(chapter.document().plainText()).contains("Capítulo 1", "Um.", "Capítulo 2", "Dois.");
        });
    }

    @Test
    void plainTextWithoutChaptersBecomesOneChapter() {
        var plan = plan(docx(null, p(null, run("Só um texto corrido."))), ChapterSplitter.Mode.AUTO);

        assertThat(plan.rule()).isEqualTo(ChapterSplitter.SplitRule.SINGLE);
        assertThat(plan.chapters()).singleElement().extracting(ChapterSplitter.Draft::title).isEqualTo("livro");
    }

    @Test
    void skipsDeletedRevisionsAndKeepsLineBreaksAndToggledOffBold() {
        byte[] docx = docx(null,
                p(null, run("Verso um") + "<w:r><w:br/><w:t>verso dois</w:t></w:r>"
                        + "<w:del><w:r><w:delText>apagado</w:delText></w:r></w:del>"
                        + "<w:r><w:rPr><w:b w:val=\"0\"/></w:rPr><w:t> normal</w:t></w:r>"));

        String json = plan(docx, ChapterSplitter.Mode.AUTO).chapters().getFirst().document().json();

        assertThat(json).contains("{\"type\":\"hardBreak\"}").doesNotContain("apagado").doesNotContain("bold");
    }

    @Test
    void warnsAboutWhatWasLeftOut() {
        byte[] docx = docx(null,
                p(null, run("Texto") + "<w:r><w:drawing><w:t>legenda escondida</w:t></w:drawing></w:r>"
                        + "<w:r><w:footnoteReference w:id=\"1\"/></w:r>"));

        var plan = plan(docx, ChapterSplitter.Mode.AUTO);

        assertThat(plan.chapters().getFirst().document().plainText()).isEqualTo("Texto");
        assertThat(plan.warnings()).containsExactly(
                "1 imagem ou caixa de texto ficou de fora: o Booksmith ainda não importa imagens.",
                "1 nota de rodapé ficou de fora.");
    }

    @Test
    void rejectsFilesThatAreNotDocx() {
        assertThatThrownBy(() -> DocxReader.read("isto não é um zip".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("docx_invalid"));
        assertThatThrownBy(() -> DocxReader.read(zip("outro.xml", "<a/>", null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("docx_invalid"));
    }

    @Test
    void neverResolvesExternalEntities() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE w:document [<!ENTITY segredo SYSTEM "file:///etc/hostname">]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&segredo;</w:t></w:r></w:p></w:body>
                </w:document>
                """;

        assertThatThrownBy(() -> DocxReader.read(zip("word/document.xml", xxe, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("docx_invalid"));
    }

    // ===== Montagem de .docx =====

    private ChapterSplitter.Plan plan(byte[] docx, ChapterSplitter.Mode mode) {
        return ChapterSplitter.plan(DocxReader.read(docx), mode, "livro.docx", mapper);
    }

    private static String p(String style, String runs) {
        String props = style == null ? "" : "<w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr>";
        return "<w:p>" + props + runs + "</w:p>";
    }

    private static String run(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    private static String italic(String text) {
        return "<w:r><w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    private static byte[] docx(String styles, String... paragraphs) {
        String document = "<w:document xmlns:w=\"" + DocxReader.W + "\"><w:body>"
                + String.join("", paragraphs) + "</w:body></w:document>";
        return zip("word/document.xml", document, styles == null ? null : "word/styles.xml", styles);
    }

    private static byte[] zip(String name, String content, String secondName, String secondContent) {
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (secondName != null) {
                zip.putNextEntry(new ZipEntry(secondName));
                zip.write(secondContent.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
