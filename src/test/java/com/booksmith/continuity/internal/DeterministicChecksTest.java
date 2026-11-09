package com.booksmith.continuity.internal;

import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.BookText;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;
import com.booksmith.universe.api.Universes.KnownEntity;
import com.booksmith.universe.api.Universes.Trait;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicChecksTest {

    private final KnownEntity lia = character("Lia", List.of("Li"), Map.of(
            "eyeColor", new Trait("green", "Verdes"),
            "hairColor", new Trait("red", "Ruivo")));
    private final KnownEntity teo = character("Teo", List.of(), Map.of("eyeColor", new Trait("dark_brown", "Castanho-escuros")));
    private final KnownEntity kael = character("Kael", List.of(), Map.of());
    private final KnownEntity cafe = new KnownEntity(UUID.randomUUID(), "place", "Café da Esquina", List.of(), Map.of());
    private final List<KnownEntity> universe = List.of(lia, teo, kael, cafe);

    // ===== Atributo fixo =====

    @Test
    void flagsEyeColorThatContradictsTheSheet() {
        var findings = attributes("Lia entrou no café. Os olhos azuis de Lia brilhavam.");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.message()).isEqualTo("Na ficha, olhos de Lia: verdes. O texto diz \"azuis\".");
            assertThat(f.evidence().getFirst().quote()).isEqualTo("Os olhos azuis de Lia brilhavam.");
            assertThat(f.evidence().getFirst().highlight()).isEqualTo("azuis");
            assertThat(f.confidence()).isEqualByComparingTo("0.90");
        });
    }

    @Test
    void acceptsColorsThatMatchOrAreCloseEnough() {
        assertThat(attributes("Lia tinha olhos verdes e cabelos acobreados.")).isEmpty();
        assertThat(attributes("Os olhos de Teo eram castanhos.")).isEmpty();
        assertThat(attributes("Teo tinha olhos escuros.")).isEmpty();
        assertThat(attributes("Os olhos de Lia eram tão verde-claros.")).isEmpty();
    }

    @Test
    void attributesTheColorToTheRightCharacter() {
        // "de Teo" decide: são os olhos do Teo, que são castanhos na ficha
        var findings = attributes("Lia encarou os olhos azuis de Teo.");

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.message()).startsWith("Na ficha, olhos de Teo"));
    }

    @Test
    void staysQuietWhenItCannotTellWhoseEyesTheyAre() {
        assertThat(attributes("Lia e Teo se olharam; olhos azuis no escuro.")).isEmpty();
        assertThat(attributes("Seus olhos azuis brilhavam.")).isEmpty();
        assertThat(attributes("Lia não tinha olhos azuis.")).isEmpty();
        assertThat(attributes("Lia fixou os olhos na parede verde.")).isEmpty();
    }

    @Test
    void groupsTheSameContradictionAcrossChapters() {
        BookText book = book(List.of("Li prendeu o cabelo loiro."), List.of("O cabelo loiro de Lia molhou na chuva."));

        var findings = AttributeCheck.run(book, universe);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.message()).isEqualTo("Na ficha, cabelo de Lia: ruivo. O texto diz \"loiro\" em 2 trechos.");
            assertThat(f.evidence()).hasSize(2);
        });
    }

    @Test
    void ignoresFreeTextOnTheSheet() {
        KnownEntity custom = character("Bia", List.of(), Map.of("eyeColor", new Trait("verde-água", "verde-água")));

        assertThat(AttributeCheck.run(book(List.of("Bia tinha olhos azuis.")), List.of(custom))).isEmpty();
    }

    // ===== Grafia =====

    @Test
    void flagsNamesThatAlmostMatchAKnownName() {
        var findings = NamingCheck.run(book(List.of("Kaell chegou cedo.", "Depois Kaell saiu.")), universe);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.message()).isEqualTo(
                    "\"Kaell\" aparece 2 vezes no texto e é quase igual a \"Kael\" (o personagem Kael). É o mesmo nome?");
            assertThat(f.evidence()).hasSize(2);
        });
    }

    @Test
    void ignoresKnownNamesShortWordsAndCommonWordsAtTheStartOfASentence() {
        BookText book = book(List.of(
                "Kael e Lia foram ao Café da Esquina.",
                "Kaem? Não.", // palavra que aparece em minúsculas no livro não é nome
                "Ele disse kaem baixinho.",
                "Teu nome é Teo."));

        assertThat(NamingCheck.run(book, universe)).isEmpty();
    }

    // ===== Utilidades =====

    @Test
    void distanceCountsTranspositionsAsOneEdit() {
        assertThat(TextScan.distance("kael", "kaell")).isEqualTo(1);
        assertThat(TextScan.distance("kael", "keal")).isEqualTo(1);
        assertThat(TextScan.distance("lia", "lia")).isZero();
    }

    private List<Finding> attributes(String paragraph) {
        return AttributeCheck.run(book(List.of(paragraph)), universe);
    }

    @SafeVarargs
    private static BookText book(List<String>... chapters) {
        List<ChapterText> list = new ArrayList<>();
        int position = 1;
        for (List<String> paragraphs : chapters) {
            List<Block> blocks = paragraphs.stream().map(p -> new Block("b_" + UUID.randomUUID(), p)).toList();
            list.add(new ChapterText(UUID.randomUUID(), position, "Capítulo " + position, blocks));
            position++;
        }
        return new BookText(UUID.randomUUID(), "Livro", UUID.randomUUID(), list);
    }

    private static KnownEntity character(String name, List<String> aliases, Map<String, Trait> appearance) {
        return new KnownEntity(UUID.randomUUID(), "character", name, aliases, appearance);
    }
}
