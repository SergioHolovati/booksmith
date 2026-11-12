package com.booksmith.assist.internal;

import com.booksmith.assist.internal.IdeaModel.Answer;
import com.booksmith.assist.internal.IdeaModel.Source;
import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;
import com.booksmith.universe.api.Universes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AskServiceTest {

    private final IdeaModel model = mock(IdeaModel.class);
    private final SuggestionLogRepository repository = mock(SuggestionLogRepository.class);
    private final Manuscripts manuscripts = mock(Manuscripts.class);
    private final Universes universes = mock(Universes.class);
    private final AiProperties properties = new AiProperties("anthropic", 10, 1,
            new AiProperties.Anthropic("", "claude-haiku-5-5"));
    private final AskService service = new AskService(
            new AiUsage(model, properties, repository, Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC)),
            manuscripts, universes);

    private final UUID owner = UUID.randomUUID();
    private final UUID book = UUID.randomUUID();
    private final UUID universe = UUID.randomUUID();
    private final UUID chapter1 = UUID.randomUUID();
    private final UUID chapter2 = UUID.randomUUID();

    private final List<ChapterText> chapters = List.of(
            new ChapterText(chapter1, 1, "A carta", List.of(
                    new Block("a1", "Tomás partiu para a capital na segunda-feira."),
                    new Block("a2", "Lia queimou a carta no fogão e não contou para ninguém."))),
            new ChapterText(chapter2, 2, "Dona Rosa", List.of(
                    new Block("b1", " "),
                    new Block("b2", "— Eu sei o que dizia aquela carta, menina — disse Dona Rosa."))));

    AskServiceTest() {
        when(model.provider()).thenReturn("anthropic");
        when(model.model()).thenReturn("claude-haiku-5-5");
        when(manuscripts.bookText(owner, book)).thenReturn(new Manuscripts.BookText(book, "Café em Vel", universe,
                chapters));
        when(manuscripts.bookContent(owner, book)).thenReturn(new Manuscripts.BookContent(book, "Café em Vel",
                "Lia herda um café que guarda memórias.", "pt-BR", false, null, null, universe, List.of()));
        when(manuscripts.openNotes(owner, book)).thenReturn(List.of());
        when(universes.brief(eq(universe), any())).thenReturn(new Universes.Brief("Vel", List.of(),
                List.of("Lia (protagonista) — aparência: Olhos verdes"), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void answersWithSourcesCheckedAgainstTheBook() {
        when(model.answer(any())).thenReturn(new Answer("Ninguém deveria saber: Lia queimou a carta sozinha (cap. 1).",
                List.of(new Source(1, "queimou a carta no fogão"),
                        new Source(1, "EU SEI O QUE DIZIA AQUELA CARTA"),
                        new Source(2, "uma frase que não está no livro"),
                        new Source(1, "“queimou a carta no fogão”"))));

        var result = service.ask(owner, new AskService.Request(book, "Quem sabe o que dizia a carta?"));

        assertThat(result.answer()).startsWith("Ninguém deveria saber");
        // a segunda fonte estava no capítulo 2, não no 1; a terceira não existe; a quarta repete a primeira
        assertThat(result.citations()).containsExactly(
                new AskService.Citation(chapter1, 1, "A carta", "a2", "queimou a carta no fogão"),
                new AskService.Citation(chapter2, 2, "Dona Rosa", "b2", "Eu sei o que dizia aquela carta"));
        ArgumentCaptor<IdeaModel.Prompt> prompt = ArgumentCaptor.forClass(IdeaModel.Prompt.class);
        verify(model).answer(prompt.capture());
        assertThat(prompt.getValue().system()).contains("Nunca invente");
        assertThat(prompt.getValue().user())
                .startsWith("Pergunta do autor: Quem sabe o que dizia a carta?")
                .contains("Lia (protagonista) — aparência: Olhos verdes")
                .contains("<capitulo numero=\"2\" titulo=\"Dona Rosa\">\n— Eu sei o que dizia");
    }

    @Test
    void invalidQuestionsNeverReachTheModel() {
        assertThatThrownBy(() -> service.ask(owner, new AskService.Request(book, "  ")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ask_invalid"));
        assertThatThrownBy(() -> service.ask(owner, new AskService.Request(book, "a".repeat(501))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ask_invalid"));
        when(manuscripts.bookText(owner, book)).thenReturn(new Manuscripts.BookText(book, "Vazio", null,
                List.of(new ChapterText(chapter1, 1, "Nada", List.of(new Block("x", " "))))));
        assertThatThrownBy(() -> service.ask(owner, new AskService.Request(book, "Quem é Lia?")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("book_empty"));
        verify(model, never()).answer(any());
    }

    @Test
    void anEmptyAnswerIsABadAnswer() {
        when(model.answer(any())).thenReturn(new Answer(" ", List.of()));
        assertThatThrownBy(() -> service.ask(owner, new AskService.Request(book, "Quem é Lia?")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_bad_answer"));
    }

    @Test
    void aSmallBookGoesWhole() {
        String text = AskService.bookText(chapters, "Quem sabe da carta?", 10_000);
        assertThat(text).contains("Tomás partiu").contains("Lia queimou").contains("Eu sei").doesNotContain("[…]");
    }

    @Test
    void aBigBookSendsTheRelevantParagraphsAndTheirNeighbors() {
        List<Block> blocks = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            blocks.add(new Block("p" + i, "Parágrafo " + i + " sobre o café e a ponte, sem nada de especial."));
        }
        blocks.set(20, new Block("p20", "O incêndio da casa da mãe começou na cozinha."));
        ChapterText big = new ChapterText(chapter1, 1, "Longo", blocks);

        String text = AskService.bookText(List.of(big), "Onde começou o incêndio?", 300);

        assertThat(text).contains("O incêndio da casa da mãe").contains("Parágrafo 19").contains("Parágrafo 21")
                .contains("[…]").doesNotContain("Parágrafo 5 ");
        assertThat(AskService.terms("Onde começou o incêndio?")).containsExactly("comecou", "incendio");
    }
}
