package com.booksmith.manuscript.internal;

import com.booksmith.manuscript.internal.OutlineParser.Item;
import com.booksmith.manuscript.internal.OutlineParser.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutlineParserTest {

    @Test
    void readsPartsChaptersUnnumberedPagesAndSubchapters() {
        var items = OutlineParser.parse("""
                Apresentação
                Parte 1 - O chamado
                Introdução ................ 7
                Capítulo 1 - Cheio do Espírito ..... 12
                  Natureza divina
                  O poder do Espírito
                2 - O legado
                2.1 - O mover de Deus
                - Mover x movimento
                Parte 2
                Lição 1: O grupo
                Conclusão ............ 98
                """);

        assertThat(items).extracting(Item::kind).containsExactly(Kind.UNNUMBERED, Kind.PART, Kind.UNNUMBERED, Kind.CHAPTER,
                Kind.SECTION, Kind.SECTION, Kind.CHAPTER, Kind.SECTION, Kind.SECTION, Kind.PART, Kind.CHAPTER,
                Kind.UNNUMBERED);
        assertThat(items).extracting(Item::title).contains("Introdução", "Capítulo 1 - Cheio do Espírito", "Natureza divina",
                "Mover x movimento", "Parte 2", "Conclusão");
    }

    @Test
    void theNumberOfPartsAndLabelledChaptersIsNotAPageNumber() {
        assertThat(OutlineParser.stripPage("Parte 2")).isEqualTo("Parte 2");
        assertThat(OutlineParser.stripPage("Dia 5")).isEqualTo("Dia 5");
        assertThat(OutlineParser.stripPage("O fim da jornada 87")).isEqualTo("O fim da jornada");
        assertThat(OutlineParser.stripPage("Epílogo 87")).isEqualTo("Epílogo 87");
        assertThat(OutlineParser.stripPage("Epílogo ....... 87")).isEqualTo("Epílogo");
    }

    @Test
    void aSubchapterWithoutAChapterBeforeItBecomesAChapter() {
        var items = OutlineParser.parse("  Primeiro tópico\n1.1 - Outro");

        assertThat(items).extracting(Item::kind).containsExactly(Kind.CHAPTER, Kind.SECTION);
    }

    @Test
    void theSameTitleWithOrWithoutLabelHasTheSameKey() {
        assertThat(OutlineParser.titleKey("Capítulo 1 - Seja cheio do Espírito"))
                .isEqualTo(OutlineParser.titleKey("1 - Seja cheio do Espírito."))
                .isEqualTo("seja cheio do espirito");
        assertThat(OutlineParser.number("Capítulo 12 - Honra")).isEqualTo("12");
        assertThat(OutlineParser.number("2.3 - Disciplina")).isEqualTo("2.3");
        assertThat(OutlineParser.number("Honra")).isNull();
        assertThat(OutlineParser.similarity("existe um legado para ser recebido", "existe um legado a ser recebido"))
                .isGreaterThan(0.7);
    }

    @Test
    void decorativeLinesAndBlankLinesAreSkipped() {
        assertThat(OutlineParser.parse("Introdução\n\n__________\n* * *\nCapítulo 1")).hasSize(2);
    }
}
