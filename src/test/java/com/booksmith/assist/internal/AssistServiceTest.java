package com.booksmith.assist.internal;

import com.booksmith.assist.internal.AssistService.ContextLine;
import com.booksmith.assist.internal.AssistService.Request;
import com.booksmith.assist.internal.IdeaModel.Idea;
import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.universe.api.Universes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    private final IdeaModel model = mock(IdeaModel.class);
    private final SuggestionLogRepository repository = mock(SuggestionLogRepository.class);
    private final ReviewRunRepository runs = mock(ReviewRunRepository.class);
    private final Universes universes = mock(Universes.class);
    private final Manuscripts manuscripts = mock(Manuscripts.class);
    private final AiProperties properties = new AiProperties("anthropic", 3, 1,
            new AiProperties.Anthropic("", "claude-opus-5-5"));
    private final AssistService service = new AssistService(
            new AiUsage(model, properties, repository, runs, Clock.fixed(NOW, ZoneOffset.UTC)), universes, manuscripts);

    private final UUID owner = UUID.randomUUID();
    private final UUID universe = UUID.randomUUID();
    private final UUID book = UUID.randomUUID();

    AssistServiceTest() {
        when(model.provider()).thenReturn("anthropic");
        when(model.model()).thenReturn("qwen3:8b");
        when(model.local()).thenReturn(true);
        when(universes.brief(eq(universe), any())).thenReturn(new Universes.Brief("Vel",
                List.of("Gêneros: Mistério, Fantasia urbana"), List.of("Lia (protagonista)", "Tomás"), List.of("Café do Porto"),
                List.of("Lia é irmã/irmão de Tomás"), List.of("Religiões: A Maré"), List.of("Ano 312 — A grande enchente")));
        when(model.ideas(any())).thenReturn(List.of(
                new Idea("Medo do silêncio", "E se Lia tivesse medo de ficar sozinha no café à noite?"),
                new Idea("  ", "  Talvez ela evite o mar.  "),
                new Idea("Vazia", " ")));
    }

    private Request characterRequest(String current) {
        return new Request("character", null, universe, null, "fears", "Medos", current,
                List.of(new ContextLine("Nome", "Lia"), new ContextLine("Personalidade", "curiosa e teimosa"),
                        new ContextLine("Ocupação", "")),
                "algo ligado ao mar");
    }

    @Test
    void characterIdeasUseTheScreenAndTheUniverseAndAreLoggedWithoutText() {
        var result = service.suggest(owner, characterRequest("Tem medo de altura."));

        assertThat(result.suggestions()).containsExactly(
                new Idea("Medo do silêncio", "E se Lia tivesse medo de ficar sozinha no café à noite?"),
                new Idea("", "Talvez ela evite o mar."));
        assertThat(result.provider()).isEqualTo("anthropic");
        assertThat(result.local()).isTrue();

        ArgumentCaptor<IdeaModel.Prompt> prompt = ArgumentCaptor.forClass(IdeaModel.Prompt.class);
        verify(model).ideas(prompt.capture());
        assertThat(prompt.getValue().system()).contains("Nunca escreva a história").contains("exatamente 4 ideias");
        assertThat(prompt.getValue().user())
                .startsWith("Pedido: ideias para o campo \"Medos\" do personagem.\nO autor acrescentou: algo ligado ao mar")
                .contains("O que está escrito no campo \"Medos\":\nTem medo de altura.")
                .contains("- Nome: Lia\n- Personalidade: curiosa e teimosa\n")
                .doesNotContain("Ocupação")
                .contains("Universo \"Vel\":\n- Gêneros: Mistério, Fantasia urbana")
                .contains("Personagens do universo:\n- Lia (protagonista)\n- Tomás")
                .contains("Relações entre personagens:\n- Lia é irmã/irmão de Tomás")
                .contains("Fichas do mundo:\n- Religiões: A Maré")
                .contains("Linha do tempo:\n- Ano 312 — A grande enchente")
                .endsWith("</material>");
        verify(universes).requireOwned(owner, universe);

        ArgumentCaptor<SuggestionLog> log = ArgumentCaptor.forClass(SuggestionLog.class);
        verify(repository).save(log.capture());
        assertThat(log.getValue().getKind()).isEqualTo("character");
        assertThat(log.getValue().getField()).isEqualTo("fears");
        assertThat(log.getValue().getMode()).isEqualTo("ideas");
        assertThat(log.getValue().getIdeaCount()).isEqualTo(2);
    }

    @Test
    void questionsModeAsksInsteadOfSuggesting() {
        service.suggest(owner, new Request("universe", "questions", universe, null, "dangers", "Perigos", "", null, null));

        ArgumentCaptor<IdeaModel.Prompt> prompt = ArgumentCaptor.forClass(IdeaModel.Prompt.class);
        verify(model).ideas(prompt.capture());
        assertThat(prompt.getValue().system()).contains("exatamente 4 perguntas").contains("Nunca responda");
        assertThat(prompt.getValue().user()).startsWith("Pedido: perguntas para o campo \"Perigos\" do universo.")
                .contains("O que está escrito no campo \"Perigos\":\n(vazio)");
    }

    @Test
    void plotIdeasReadTheSynopsisChaptersAndTheEndOfTheLatestChapter() {
        when(manuscripts.bookContent(owner, book)).thenReturn(new Manuscripts.BookContent(book, "Café em Vel",
                "Uma barista descobre que o café guarda memórias.", "pt-BR", false, null, null, universe, List.of()));
        when(manuscripts.bookText(owner, book)).thenReturn(new Manuscripts.BookText(book, "Café em Vel", universe,
                List.of(new Manuscripts.ChapterText(UUID.randomUUID(), 1, "A chegada",
                                List.of(new Manuscripts.Block("b1", "Lia abriu o café."))),
                        new Manuscripts.ChapterText(UUID.randomUUID(), 2, "",
                                List.of(new Manuscripts.Block("b2", " "))))));

        when(manuscripts.openNotes(owner, book)).thenReturn(List.of("Cap. 1: Tomás mente sobre o incêndio"));

        service.suggest(owner, new Request("plot", "ideas", null, book, "twist", null, null, null, null));

        ArgumentCaptor<IdeaModel.Prompt> prompt = ArgumentCaptor.forClass(IdeaModel.Prompt.class);
        verify(model).ideas(prompt.capture());
        assertThat(prompt.getValue().user())
                .startsWith("Pedido: ideias para o enredo do livro, de reviravoltas")
                .contains("Sinopse:\nUma barista descobre que o café guarda memórias.")
                .contains("Notas do autor (planos e lembretes):\n- Cap. 1: Tomás mente sobre o incêndio")
                .contains("Capítulos até agora, com o começo de cada um:\n- 1. A chegada — começa: Lia abriu o café.\n"
                        + "- 2. (sem título)")
                .contains("Final do capítulo 1 (o mais recente com texto):\nLia abriu o café.")
                .contains("Personagens:\n- Lia (protagonista)\n- Tomás")
                .contains("Relações entre personagens:\n- Lia é irmã/irmão de Tomás");
    }

    @Test
    void theCharacterSheetFocusesTheCharacterAndKnowsTheBooksOfTheUniverse() {
        UUID lia = UUID.randomUUID();
        when(manuscripts.booksInUniverse(owner, universe)).thenReturn(List.of(
                new Manuscripts.BookSummary(book, "Café em Vel", "Uma barista\ndescobre memórias."),
                new Manuscripts.BookSummary(UUID.randomUUID(), "Rascunho", null)));

        service.suggest(owner, new Request("character", null, universe, null, "fears", "Medos", "", null, null, lia,
                "Do que tem medo, o que evita"));

        verify(universes).brief(universe, lia);
        ArgumentCaptor<IdeaModel.Prompt> prompt = ArgumentCaptor.forClass(IdeaModel.Prompt.class);
        verify(model).ideas(prompt.capture());
        assertThat(prompt.getValue().user())
                .startsWith("Pedido: ideias para o campo \"Medos\" do personagem (Do que tem medo, o que evita).")
                .contains("Livros que se passam neste universo:\n- \"Café em Vel\": Uma barista descobre memórias.\n"
                        + "- \"Rascunho\"\n")
                .contains("Outros personagens do universo:");
        assertThat(prompt.getValue().system()).contains("Nada de ideias genéricas");
    }

    @Test
    void dailyLimitStopsBeforeCallingTheModel() {
        when(repository.countByUserIdAndKindNotAndCreatedAtAfter(eq(owner), eq("review"), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.suggest(owner, characterRequest("")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("ai_daily_limit");
                    assertThat(e.getStatus().value()).isEqualTo(429);
                });
        verify(model, never()).ideas(any());
        assertThat(service.status(owner).remaining()).isZero();
    }

    @Test
    void someoneElsesUniverseIsNotFound() {
        doThrow(ApiException.notFound("universe_not_found", "Universo não encontrado."))
                .when(universes).requireOwned(owner, universe);

        assertThatThrownBy(() -> service.suggest(owner, characterRequest("")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("universe_not_found"));
        verify(model, never()).ideas(any());
    }

    @Test
    void rejectsUnknownKindsAndOversizedInput() {
        assertThatThrownBy(() -> service.suggest(owner,
                new Request("chapter", null, universe, null, "x", null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("suggestion_invalid"));
        assertThatThrownBy(() -> service.suggest(owner,
                new Request("plot", null, null, book, "middle", null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("enredo"));
        assertThatThrownBy(() -> service.suggest(owner, characterRequest("x".repeat(4_001))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("4000"));
        verify(model, never()).ideas(any());
    }

    @Test
    void anEmptyAnswerIsAnError() {
        when(model.ideas(any())).thenReturn(List.of(new Idea("Só título", "")));

        assertThatThrownBy(() -> service.suggest(owner, characterRequest("")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_bad_answer"));
        verify(repository, never()).save(any());
    }

    @Test
    void statusShowsTheProviderAndWhatIsLeftToday() {
        when(repository.countByUserIdAndKindNotAndCreatedAtAfter(owner, "review", NOW.minus(Duration.ofDays(1)))).thenReturn(1L);

        var status = service.status(owner);

        assertThat(status.enabled()).isTrue();
        assertThat(status.model()).isEqualTo("qwen3:8b");
        assertThat(status.dailyLimit()).isEqualTo(3);
        assertThat(status.remaining()).isEqualTo(2);
    }
}
