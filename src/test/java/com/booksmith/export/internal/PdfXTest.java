package com.booksmith.export.internal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * O PDF para gráfica com o Ghostscript de verdade: no notebook, o de {@code tools/gs}; no servidor e na integração
 * contínua, o {@code gs} do sistema. Sem nenhum dos dois, os testes são pulados.
 */
class PdfXTest {

    private static PdfX pdfx;

    @BeforeAll
    static void ghostscript() {
        Path local = Path.of("..", "tools", "gs", "bin", "gswin64c.exe");
        pdfx = new PdfX(Files.exists(local) ? local.toAbsolutePath().toString() : "gs", Duration.ofMinutes(2));
    }

    @Test
    void aPictureBookBecomesPdfX1aInCmykWithTheCutsKept() throws Exception {
        assumeTrue(pdfx.available(), "Ghostscript não instalado");
        ExportBook book = ExportWritersTest.pictureBook();
        byte[] rgb = PdfWriter.write(book);
        byte[] print = pdfx.convert(rgb, "A Última Maga — ação!");

        assertThat(new String(print, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.3");
        try (PDDocument original = Loader.loadPDF(rgb); PDDocument document = Loader.loadPDF(print)) {
            assertThat(document.getDocumentInformation().getCustomMetadataValue("GTS_PDFXVersion"))
                    .isEqualTo("PDF/X-1a:2001");
            // O título é o do livro (o do PDF original vale mais que o da definição)
            assertThat(document.getDocumentInformation().getTitle())
                    .isEqualTo(original.getDocumentInformation().getTitle());
            var intents = document.getDocumentCatalog().getOutputIntents();
            assertThat(intents).hasSize(1);
            assertThat(intents.getFirst().getOutputConditionIdentifier()).isEqualTo("FOGRA39");
            assertThat(intents.getFirst().getDestOutputIntent()).isNotNull();
            assertThat(document.getNumberOfPages()).isEqualTo(original.getNumberOfPages());
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDPage page = document.getPage(i);
                assertThat(page.getCOSObject().containsKey(COSName.TRIM_BOX)).isTrue();
                // Com sangria, o corte fica dentro da página, como no original
                assertThat(page.getTrimBox().getWidth()).isCloseTo(original.getPage(i).getTrimBox().getWidth(), within(0.05f));
                assertThat(page.getTrimBox().getWidth()).isLessThan(page.getMediaBox().getWidth());
            }
        }
        assertThat(new String(print, StandardCharsets.ISO_8859_1)).doesNotContain("/DeviceRGB");
    }

    @Test
    void blackTextGoesOnlyOnTheBlackPlate() throws Exception {
        assumeTrue(pdfx.available(), "Ghostscript não instalado");
        byte[] print = pdfx.convert(PdfWriter.write(ExportWritersTest.withFullStructure()), "Romance");
        List<String> colors = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(print)) {
            for (PDPage page : document.getPages()) {
                // Sem links (o PDF/X não aceita) e com o corte na página inteira (livro sem sangria)
                assertThat(page.getAnnotations()).isEmpty();
                assertThat(page.getTrimBox().getWidth()).isCloseTo(page.getMediaBox().getWidth(), within(0.05f));
                List<Object> tokens = new PDFStreamParser(page).parse();
                for (int i = 0; i < tokens.size(); i++) {
                    if (tokens.get(i) instanceof Operator op && List.of("g", "k", "rg").contains(op.getName())) {
                        colors.add(op.getName());
                    }
                }
            }
        }
        // O texto preto sai em escala de cinza (só a chapa preta), nunca em RGB nem como preto das quatro tintas
        assertThat(colors).contains("g").doesNotContain("rg");
    }

    @Test
    void theTitleGoesInUtf16AndThePathIsEscaped() throws Exception {
        String definition = PdfX.definition(Path.of("/tmp/a (b)/perfil.icc"), "Ação");
        assertThat(definition).contains("/Title <FEFF004100E700E3006F>");
        assertThat(definition).contains("/a \\(b\\)/perfil.icc) (r) file");
        assertThat(definition).contains("(PDF/X-1a:2001)", "(FOGRA39)");
    }
}
