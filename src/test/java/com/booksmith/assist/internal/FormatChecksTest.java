package com.booksmith.assist.internal;

import com.booksmith.assist.internal.ReviewService.Item;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.BookText;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FormatChecksTest {

    private static int blockCount;

    private static ChapterText chapter(int position, String... paragraphs) {
        List<Block> blocks = new ArrayList<>();
        for (String p : paragraphs) {
            blocks.add(new Block("b" + blockCount++, p));
        }
        return new ChapterText(UUID.randomUUID(), position, "Capítulo " + position, blocks);
    }

    private static BookText book(ChapterText... chapters) {
        return new BookText(UUID.randomUUID(), "A conversão", null, List.of(chapters));
    }

    /** Os capítulos anteriores usam travessão e "Dona Lurdes". */
    private static final ChapterText EARLIER = chapter(1,
            "— Você viu a Dona Lurdes hoje? — perguntou Giulia.",
            "— Não. A Dona Lurdes não abriu a janela.",
            "— Estranho — eu disse.",
            "— Vamos lá ver.",
            "— Agora não.",
            "— Por quê?",
            "A Dona Lurdes morava ali havia trinta anos.");

    private static final ChapterText FOURTH = chapter(4,
            "Não tínhamos nenhum outro sinal de transformação além da cura milagrosa.",
            "\"A dona Lurdes não tinha machucado nenhum, só se transformou sozinha\" respondeu Giulia.",
            "\"Mas ser ferido, te transforma? igual filme de zumbi, sabe?\" perguntou Ana.",
            "Não tínhamos como saber.",
            "\"próxima pergunta, como matamos essas coisas?\" Perguntou Ana.",
            "\"duvido muito. Não acho que ninguém tinha como");

    private static List<Item> fourth() {
        return FormatChecks.check(book(EARLIER, FOURTH), FOURTH);
    }

    @Test
    void everyQuotedSpeechComesConvertedToDashes() {
        assertThat(fourth()).filteredOn(i -> "dialogue".equals(i.kind()))
                .extracting(Item::suggestion)
                .containsExactly(
                        "— A dona Lurdes não tinha machucado nenhum, só se transformou sozinha — respondeu Giulia.",
                        "— Mas ser ferido, te transforma? igual filme de zumbi, sabe? — perguntou Ana.",
                        "— Próxima pergunta, como matamos essas coisas? — perguntou Ana.",
                        "— Duvido muito. Não acho que ninguém tinha como");
        assertThat(fourth()).filteredOn(i -> "dialogue".equals(i.kind())).allSatisfy(item -> {
            assertThat(item.fix()).isTrue();
            assertThat(item.problem()).contains("aspas").contains("travessão");
            assertThat(FOURTH.blocks()).anySatisfy(b -> assertThat(b.text()).isEqualTo(item.quote()));
        });
        // os parágrafos convertidos já saem com a fala em maiúscula: não repete o aviso de minúscula
        assertThat(fourth()).noneMatch(i -> "capital".equals(i.kind()));
    }

    @Test
    void conversionFollowsTheBrazilianDashConvention() {
        assertThat(FormatChecks.toDashes("\"Precisa ser machucado pra se transformar?\" Eu perguntei."))
                .isEqualTo("— Precisa ser machucado pra se transformar? — eu perguntei.");
        assertThat(FormatChecks.toDashes("\"Tem que furar o olho. Quando tentei na barriga a faca quebrou\" respondi."))
                .isEqualTo("— Tem que furar o olho. Quando tentei na barriga a faca quebrou — respondi.");
        assertThat(FormatChecks.toDashes("“Quebrou.” disse ela. “Agora vem.”"))
                .isEqualTo("— Quebrou — disse ela. — Agora vem.");
        assertThat(FormatChecks.toDashes("\"Sim,\" Ana disse."))
                .isEqualTo("— Sim — Ana disse.");
        assertThat(FormatChecks.toDashes("\"Vou embora.\" E bateu a porta."))
                .isEqualTo("— Vou embora. — E bateu a porta.");
    }

    @Test
    void speechStartingInLowercaseGetsACapital() {
        ChapterText dashes = chapter(5, "— duvido — disse Ana.", "— Por quê?", "— sei lá.");
        assertThat(FormatChecks.check(book(EARLIER, dashes), dashes)).filteredOn(i -> "capital".equals(i.kind()))
                .extracting(Item::quote, Item::suggestion, Item::fix)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("— duvido", "— Duvido", true),
                        org.assertj.core.groups.Tuple.tuple("— sei", "— Sei", true));
    }

    @Test
    void honorificWrittenTheLessUsedWay() {
        assertThat(fourth()).filteredOn(i -> "Dona Lurdes".equals(i.suggestion()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.quote()).isEqualTo("dona Lurdes");
                    assertThat(item.fix()).isTrue();
                    assertThat(item.problem()).contains("3 vez(es)");
                });
    }

    @Test
    void sameSpeechTagTwoParagraphsApart() {
        assertThat(fourth()).filteredOn(i -> i.problem().contains("marcação de fala"))
                .singleElement()
                .satisfies(item -> assertThat(item.quote()).isEqualTo("Perguntou Ana"));
    }

    @Test
    void aCleanChapterHasNothingToPoint() {
        ChapterText clean = chapter(2, "— Bom dia — disse Giulia.", "— Bom dia — respondi.",
                "A Dona Lurdes acenou da janela.");
        assertThat(FormatChecks.check(book(EARLIER, clean), clean)).isEmpty();
    }

    @Test
    void twoChaptersInATieAreNotPointed() {
        ChapterText dashes = chapter(1, "— Oi.", "— Oi.", "— Tudo bem?");
        ChapterText quotes = chapter(2, "\"Oi.\"", "\"Oi.\"", "\"Tudo bem?\"");
        assertThat(FormatChecks.check(book(dashes, quotes), quotes)).isEmpty();
        assertThat(FormatChecks.check(book(dashes, quotes), dashes)).isEmpty();
    }

    @Test
    void mixedStylesInTheFirstChapter() {
        ChapterText first = chapter(1, "— Oi — disse Ana.", "— Tudo bem?", "\"Tudo\" respondi.");
        assertThat(FormatChecks.check(book(first), first)).singleElement()
                .satisfies(item -> {
                    assertThat(item.problem()).contains("maioria das falas usa travessão");
                    assertThat(item.quote()).isEqualTo("\"Tudo\" respondi.");
                    assertThat(item.suggestion()).isEqualTo("— Tudo — respondi.");
                    assertThat(item.fix()).isTrue();
                });
        // com as aspas na maioria, só o aviso (converter para aspas fica para depois)
        ChapterText quotes = chapter(1, "\"Oi\" disse Ana.", "\"Tudo bem?\"", "— Tudo — respondi.");
        assertThat(FormatChecks.check(book(quotes), quotes)).singleElement()
                .satisfies(item -> {
                    assertThat(item.problem()).contains("mistura");
                    assertThat(item.fix()).isFalse();
                });
    }

    @Test
    void openingQuotesAreFoundOnlyAtTheStartOfASpeech() {
        assertThat(FormatChecks.speechStarts("\"oi\" disse ele. \"tudo bem?\"")).containsExactly(0, 16);
        // aspas no meio da frase (citação) não são começo de fala
        assertThat(FormatChecks.speechStarts("Ela leu \"perigo\" na placa.")).isEmpty();
        assertThat(FormatChecks.speechStarts("— oi")).containsExactly(0);
    }
}
