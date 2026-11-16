package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.internal.ImportedText.Paragraph;
import com.booksmith.manuscript.internal.ImportedText.Segment;
import com.booksmith.manuscript.internal.StructureBuilder.Decision;
import com.booksmith.manuscript.internal.StructureBuilder.OutlineEntry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructureBuilderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String LONG = "Texto corrido do livro, comprido o bastante para ser corpo de capítulo, com "
            + "frases inteiras e pontuação, que ninguém confundiria com um título de capítulo ou de parte.";

    private static Paragraph p(String text) {
        return new Paragraph(0, false, false, false, List.of(new Segment(text, Set.of())), false, false, 24);
    }

    private static Paragraph heading(int level, String text) {
        return new Paragraph(level, false, false, false, List.of(new Segment(text, Set.of())), false, false, 32);
    }

    private static StructureAnalyzer.Analysis analyze(Paragraph... paragraphs) {
        return StructureAnalyzer.analyze(new ImportedText.Result(List.of(paragraphs), 0, 0, 0));
    }

    /** O que a tela manda quando o escritor aprova a proposta sem mudar nada. */
    private static List<Decision> accepted(StructureAnalyzer.Analysis analysis) {
        return analysis.divisions().stream()
                .map(d -> new Decision(d.index(), d.kind().name().toLowerCase(Locale.ROOT), d.title())).toList();
    }

    private static List<OutlineEntry> outline(StructureAnalyzer.Analysis analysis) {
        List<OutlineEntry> entries = new ArrayList<>();
        for (int i = 0; i < analysis.outline().items().size(); i++) {
            var item = analysis.outline().items().get(i);
            entries.add(new OutlineEntry(item.kind().name().toLowerCase(Locale.ROOT), item.title(),
                    analysis.outline().matches().get(i)));
        }
        return entries;
    }

    private static int headings(ChapterSplitter.Draft draft) {
        return draft.document().json().split("\"heading\"", -1).length - 1;
    }

    @Test
    void theApprovedStructureBecomesChaptersPartsSubchaptersAndTheDedication() {
        var analysis = analyze(heading(1, "Dedicatória"), p("Para minha mãe."),
                heading(1, "Apresentação"), p(LONG),
                heading(3, "PARTE 1"), heading(1, "Capítulo 1 - Início"), p(LONG), p("1.1 - Primeiro passo"), p(LONG),
                heading(1, "Capítulo 2 - Meio"), p(LONG));
        List<Decision> decisions = new ArrayList<>(accepted(analysis));
        // O escritor marcou "1.1 - Primeiro passo" (texto comum no arquivo) como subcapítulo e corrigiu um título
        decisions.add(new Decision(7, "section", "1.1 - Primeiro passo"));
        decisions.replaceAll(d -> d.index() == 9 ? new Decision(9, "chapter", "Capítulo 2 - O meio") : d);

        var result = StructureBuilder.build(analysis, decisions, false, null, null, MAPPER);

        assertThat(result.dedication()).isEqualTo("Para minha mãe.");
        assertThat(result.chapters()).extracting(ChapterSplitter.Draft::title)
                .containsExactly("Apresentação", "Capítulo 1 - Início", "Capítulo 2 - O meio");
        assertThat(result.chapters().get(1).partTitle()).isEqualTo("PARTE 1");
        assertThat(headings(result.chapters().get(1))).isEqualTo(1);
        assertThat(result.chapters().get(1).document().wordCount()).isGreaterThan(2 * LONG.split(" ").length);
    }

    @Test
    void textAndRemoveChangeWhatHappensToAHeading() {
        var analysis = analyze(heading(2, "SUBTÍTULO"), heading(1, "Capítulo 1 - Início"), p(LONG),
                heading(1, "Uma frase de efeito"), p(LONG));
        List<Decision> decisions = List.of(new Decision(0, "remove", null), new Decision(1, "chapter", null),
                new Decision(3, "text", null));

        var result = StructureBuilder.build(analysis, decisions, false, null, null, MAPPER);

        assertThat(result.chapters()).singleElement().satisfies(chapter -> {
            assertThat(chapter.title()).isEqualTo("Capítulo 1 - Início");
            assertThat(headings(chapter)).isZero();
            assertThat(chapter.document().json()).contains("Uma frase de efeito").doesNotContain("SUBTÍTULO");
        });
    }

    @Test
    void withTheOutlineThePlanGivesTheOrderAndTheMissingChaptersAreCreatedEmpty() {
        var analysis = analyze(heading(1, "Apresentação"), p(LONG),
                heading(3, "PARTE 1"), p("Introdução"), p("1 - A chegada"), p("1.1 - O cais"), p("2 - A tempestade"),
                p("2.1 - O vento"), p("2.2 - A chuva"), heading(3, "PARTE 2"), p("1 - O retorno"),
                heading(2, "Introdução"), p(LONG), heading(1, "Capítulo 1 - A chegada"), p(LONG),
                heading(1, "Um texto fora do plano"), p(LONG));

        var result = StructureBuilder.build(analysis, accepted(analysis), true, outline(analysis), null, MAPPER);

        assertThat(result.chapters()).extracting(ChapterSplitter.Draft::title).containsExactly(
                "Apresentação", "Introdução", "1 - A chegada", "2 - A tempestade", "1 - O retorno", "Um texto fora do plano");
        assertThat(result.chapters()).extracting(ChapterSplitter.Draft::partTitle)
                .containsExactly(null, "PARTE 1", null, null, "PARTE 2", null);
        // Com texto: o do arquivo; vazio: só os subcapítulos planejados
        assertThat(result.chapters().get(2).document().wordCount()).isGreaterThan(20);
        assertThat(headings(result.chapters().get(3))).isEqualTo(2);
        assertThat(result.warnings()).singleElement().asString().contains("Um texto fora do plano");
    }

    @Test
    void theFileOutlineEndsTheDedication() {
        var analysis = analyze(heading(1, "Dedicatória"), p("Para minha mãe."),
                heading(1, "Sumário"), p("Introdução"), p("Capítulo 1 - Início"), p("Capítulo 2 - Meio"),
                heading(1, "Introdução"), p(LONG), heading(1, "Capítulo 1 - Início"), p(LONG));
        // O escritor diz que a "Introdução" do texto não é divisão: o texto dela não pode cair na dedicatória
        List<Decision> decisions = accepted(analysis).stream()
                .map(d -> d.title().equals("Introdução") ? new Decision(d.index(), "text", null) : d).toList();

        var result = StructureBuilder.build(analysis, decisions, false, null, null, MAPPER);

        assertThat(result.dedication()).isEqualTo("Para minha mãe.");
        assertThat(result.chapters()).extracting(ChapterSplitter.Draft::title).containsExactly("Abertura", "Capítulo 1 - Início");
        assertThat(result.chapters().getFirst().document().json()).contains("Texto corrido");
    }

    @Test
    void aPastedOutlineCreatesEmptyChaptersWithPlannedSubchapters() {
        var entries = OutlineParser.parse("Introdução\nParte 1\nCapítulo 1 - Início\n  O primeiro dia\n  A noite\nCapítulo 2 - Fim")
                .stream().map(i -> new OutlineEntry(i.kind().name().toLowerCase(Locale.ROOT), i.title(), -1)).toList();

        var result = StructureBuilder.fromOutline(entries, MAPPER);

        assertThat(result.chapters()).extracting(ChapterSplitter.Draft::title)
                .containsExactly("Introdução", "Capítulo 1 - Início", "Capítulo 2 - Fim");
        assertThat(result.chapters().get(1).partTitle()).isEqualTo("Parte 1");
        assertThat(headings(result.chapters().get(1))).isEqualTo(2);
    }

    @Test
    void invalidKindsAndIndexesAreRefused() {
        var analysis = analyze(heading(1, "Capítulo 1"), p(LONG));

        assertThatThrownBy(() -> StructureBuilder.build(analysis, List.of(new Decision(0, "capitulo", null)), false, null, null, MAPPER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("import_structure_invalid"));
        assertThatThrownBy(() -> StructureBuilder.build(analysis, List.of(new Decision(99, "chapter", null)), false, null, null, MAPPER))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> StructureBuilder.fromOutline(List.of(), MAPPER)).isInstanceOf(ApiException.class);
    }
}
