package com.booksmith.manuscript.internal;

import com.booksmith.manuscript.internal.ImportedText.Paragraph;
import com.booksmith.manuscript.internal.ImportedText.Segment;
import com.booksmith.manuscript.internal.StructureAnalyzer.Division;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StructureAnalyzerTest {

    private static final String LONG = "Era uma vez um texto comprido o bastante para ser corpo de capítulo, com frases "
            + "inteiras e pontuação, que ninguém confundiria com um título de capítulo ou de parte do livro.";

    /** Parágrafo comum (letra 12 pt) ou "título à mão": negrito e/ou letra maior, sem estilo de título. */
    private static Paragraph p(String text) {
        return new Paragraph(0, false, false, false, List.of(new Segment(text, Set.of())), false, false, 24);
    }

    private static Paragraph bold(String text, int size) {
        return new Paragraph(0, false, false, false, List.of(new Segment(text, Set.of("bold"))), false, false, size);
    }

    private static Paragraph heading(int level, String text) {
        return new Paragraph(level, false, false, false, List.of(new Segment(text, Set.of())), false, false, 32);
    }

    private static Paragraph title(String text) {
        return new Paragraph(0, true, false, false, List.of(new Segment(text, Set.of())), false, false, 48);
    }

    private static StructureAnalyzer.Analysis analyze(Paragraph... paragraphs) {
        return StructureAnalyzer.analyze(new ImportedText.Result(List.of(paragraphs), 0, 0, 0));
    }

    private static List<String> kinds(StructureAnalyzer.Analysis analysis) {
        List<String> kinds = new ArrayList<>();
        for (Division d : analysis.divisions()) {
            kinds.add(d.kind() + ":" + d.title());
        }
        return kinds;
    }

    @Test
    void chaptersMarkedByHandWithBoldAndBiggerLettersAreFound() {
        var analysis = analyze(title("O Farol"),
                bold("Capítulo 1 - A chegada", 32), p(LONG), p(LONG),
                bold("1.1 - O cais", 28), p(LONG),
                bold("Capítulo 2 - A tempestade", 32), p(LONG));

        assertThat(analysis.documentTitle()).isEqualTo("O Farol");
        assertThat(kinds(analysis)).containsExactly(
                "CHAPTER:Capítulo 1 - A chegada", "SECTION:1.1 - O cais", "CHAPTER:Capítulo 2 - A tempestade");
        Division first = analysis.divisions().getFirst();
        assertThat(first.confidence()).isEqualTo(StructureAnalyzer.Confidence.HIGH);
        assertThat(first.reasons()).contains("Começa com \"Capítulo\" e um número", "Todo em negrito",
                "Letra maior que o texto (16 pt)");
        assertThat(analysis.divisions().get(1).words()).isEqualTo(LONG.split("\\s+").length);
    }

    @Test
    void frontPagesPartsAndDedicationAreRecognized() {
        var analysis = analyze(
                heading(1, "Dedicatória"), p("Para minha mãe."),
                heading(1, "Apresentação"), p(LONG),
                heading(3, "PARTE 1"),
                heading(2, "Introdução"), p(LONG),
                heading(2, "Capítulo 1 - Começo"), p(LONG),
                heading(2, "Conclusão"), p(LONG));

        assertThat(kinds(analysis)).containsExactly("DEDICATION:Dedicatória", "UNNUMBERED:Apresentação", "PART:PARTE 1",
                "UNNUMBERED:Introdução", "CHAPTER:Capítulo 1 - Começo", "UNNUMBERED:Conclusão");
    }

    @Test
    void headingsWithoutKeywordsTakeTheirRoleFromTheirLook() {
        var analysis = analyze(
                heading(1, "A partida"), p(LONG), heading(2, "No porto"), p(LONG),
                heading(1, "O mar"), p(LONG), heading(2, "A ilha"), p(LONG));

        assertThat(kinds(analysis)).containsExactly("CHAPTER:A partida", "SECTION:No porto", "CHAPTER:O mar", "SECTION:A ilha");
    }

    @Test
    void decorativeLinesAndAnEmptySubtitleAtTheTopAreSuggestedForRemoval() {
        var analysis = analyze(title("Legado"), heading(2, "UM SUBTÍTULO DE EFEITO"), heading(3, "__________"),
                heading(1, "Capítulo 1 - Início"), p(LONG), heading(1, "Capítulo 2 - Meio"), p(LONG));

        assertThat(kinds(analysis)).containsExactly("REMOVE:UM SUBTÍTULO DE EFEITO", "REMOVE:__________",
                "CHAPTER:Capítulo 1 - Início", "CHAPTER:Capítulo 2 - Meio");
    }

    @Test
    void anUntitledNumberedPlanIsReadAsTheOutlineAndMatchedWithTheText() {
        var analysis = analyze(
                heading(1, "Apresentação"), p(LONG),
                heading(3, "PARTE 1"), p("Introdução"), p("1 - A chegada"), p("1.1 - O cais"), p("1.2 - A vila"),
                p("2 - A tempestade que vem"), p("2.1 - O vento"), p("3 - O fim"), p("3.1 - A volta"),
                heading(2, "Introdução"), p(LONG),
                bold("Capítulo 1 - A chegada", 32), p(LONG), bold("1.1 - O cais", 28), p(LONG),
                bold("Capítulo 2 - A tempestade que chega", 32), p(LONG));

        var outline = analysis.outline();
        assertThat(outline).isNotNull();
        assertThat(outline.titled()).isFalse();
        assertThat(outline.items()).extracting(OutlineParser.Item::kind).containsExactly(
                OutlineParser.Kind.PART, OutlineParser.Kind.UNNUMBERED, OutlineParser.Kind.CHAPTER, OutlineParser.Kind.SECTION,
                OutlineParser.Kind.SECTION, OutlineParser.Kind.CHAPTER, OutlineParser.Kind.SECTION, OutlineParser.Kind.CHAPTER,
                OutlineParser.Kind.SECTION);
        // Introdução, capítulo 1, 1.1 e o capítulo 2 ("vem" × "chega": mesmo número, quase o mesmo título) têm texto
        assertThat(outline.withText()).isEqualTo(4);
        assertThat(outline.matches().get(5)).isGreaterThan(0);
        assertThat(outline.matches().get(7)).isEqualTo(-1);
        // O plano não vira capítulo: só o texto de verdade
        assertThat(kinds(analysis)).containsExactly("UNNUMBERED:Apresentação", "UNNUMBERED:Introdução",
                "CHAPTER:Capítulo 1 - A chegada", "SECTION:1.1 - O cais", "CHAPTER:Capítulo 2 - A tempestade que chega");
    }

    @Test
    void aTitledSummaryEndsWhereTheBookStarts() {
        var analysis = analyze(heading(1, "Sumário"), p("Introdução ....... 5"), p("Capítulo 1 - Início ..... 9"),
                heading(1, "Introdução"), p(LONG), heading(1, "Capítulo 1 - Início"), p(LONG));

        assertThat(analysis.outline().titled()).isTrue();
        assertThat(analysis.outline().items()).extracting(OutlineParser.Item::title)
                .containsExactly("Introdução", "Capítulo 1 - Início");
        assertThat(analysis.outline().withText()).isEqualTo(2);
        assertThat(kinds(analysis)).containsExactly("UNNUMBERED:Introdução", "CHAPTER:Capítulo 1 - Início");
    }

    @Test
    void ordinarySentencesAreNeverTakenForChapters() {
        var analysis = analyze(heading(1, "Capítulo 1 - Início"), p(LONG),
                p("Dia 3 de março, ele acordou cedo e saiu sem dizer nada a ninguém, como sempre fazia quando chovia."),
                p("Introdução ao assunto que vem a seguir, para quem ainda não leu o capítulo anterior do livro inteiro."));

        assertThat(kinds(analysis)).containsExactly("CHAPTER:Capítulo 1 - Início");
    }
}
