package com.booksmith.assist.internal;

import com.booksmith.assist.internal.IdeaModel.Finding;
import com.booksmith.assist.internal.ReviewService.Item;
import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.universe.api.Universes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private final IdeaModel model = mock(IdeaModel.class);
    private final SuggestionLogRepository repository = mock(SuggestionLogRepository.class);
    private final ReviewRunRepository runs = mock(ReviewRunRepository.class);
    private final Manuscripts manuscripts = mock(Manuscripts.class);
    private final Universes universes = mock(Universes.class);
    private final AiProperties properties = new AiProperties("anthropic", 10, 1,
            new AiProperties.Anthropic("", "claude-opus-5-5"));
    private final ReviewService service = new ReviewService(
            new AiUsage(model, properties, repository, runs, Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC)),
            manuscripts, universes);

    private final UUID owner = UUID.randomUUID();
    private final UUID book = UUID.randomUUID();
    private final UUID universe = UUID.randomUUID();
    private final UUID chapter1 = UUID.randomUUID();
    private final UUID chapter2 = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();

    ReviewServiceTest() {
        when(model.provider()).thenReturn("anthropic");
        when(runs.use(eq(run), eq(owner), eq(chapter2), any())).thenReturn(1);
        when(model.model()).thenReturn("claude-haiku-5-5");
        when(model.local()).thenReturn(false);
        when(manuscripts.bookText(owner, book)).thenReturn(new Manuscripts.BookText(book, "Café em Vel", universe, List.of(
                new Manuscripts.ChapterText(chapter1, 1, "A chegada",
                        List.of(new Block("a1", "Lia herdou o café da avó."), new Block("a2", "Tomás quer o café."))),
                new Manuscripts.ChapterText(chapter2, 2, "A carta",
                        List.of(new Block("b1", "Lia abriu a porta do café e viu as xícara na prateleira."),
                                new Block("b2", " "),
                                new Block("b3", "Tomás tinha olhos azuis e mentia muito."))))));
        when(manuscripts.bookContent(owner, book)).thenReturn(new Manuscripts.BookContent(book, "Café em Vel",
                "Lia herda um café que guarda memórias.", "pt-BR", false, null, null, universe, List.of()));
        when(manuscripts.openNotes(owner, book)).thenReturn(List.of("Cap. 2: Tomás esconde o bilhete"));
        when(universes.vocabulary(universe)).thenReturn(Set.of("Vel", "Tomás", "Lia"));
        when(universes.brief(eq(universe), any())).thenReturn(new Universes.Brief("Vel", List.of(),
                List.of("Tomás (antagonista) — aparência: Olhos verdes"), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void spellingKeepsOnlyRealCorrectionsFoundInTheText() {
        when(model.findings(any())).thenReturn(List.of(
                new Finding("as xícara", "Concordância", "as xícaras"),
                new Finding("palavra que não existe no texto", "Erro", "outra"),
                new Finding("Tomás", "Nome", "Tomás"),
                new Finding("as xícara", "Concordância", "as xícaras")));

        var result = service.review(owner, new ReviewService.Request(book, chapter2, "spelling", null, run));

        assertThat(result.findings()).containsExactly(new Item("b1", "as xícara", "Concordância", "as xícaras", true));
        assertThat(result.part()).isEqualTo(1);
        assertThat(result.parts()).isEqualTo(1);
        assertThat(result.nextBlock()).isNull();

        ArgumentCaptor<IdeaModel.Prompt> prompt = ArgumentCaptor.forClass(IdeaModel.Prompt.class);
        verify(model).findings(prompt.capture());
        assertThat(prompt.getValue().system()).contains("ortografia").contains("Nunca reescreva");
        assertThat(prompt.getValue().temperature()).isEqualTo(0.2);
        assertThat(prompt.getValue().user())
                .startsWith("Revise o capítulo 2 (\"A carta\").")
                .contains("Nomes próprios do universo (não são erro):\nLia, Tomás, Vel")
                .contains("<capitulo>\nLia abriu a porta do café e viu as xícara na prateleira.\n\n"
                        + "Tomás tinha olhos azuis e mentia muito.\n</capitulo>")
                .doesNotContain("Sinopse");
    }

    @Test
    void continuityComparesWithTheSheetsAndThePreviousChapter() {
        when(model.findings(any())).thenReturn(List.of(
                new Finding("TOMÁS TINHA OLHOS AZUIS", "Na ficha, Tomás tem olhos verdes.", "Confira a cor dos olhos."),
                new Finding("", "O capítulo inteiro ignora o bilhete das notas.", "Veja se o bilhete aparece.")));

        var result = service.review(owner, new ReviewService.Request(book, chapter2, "continuity", null, run));

        assertThat(result.findings()).containsExactly(
                new Item("b3", "Tomás tinha olhos azuis", "Na ficha, Tomás tem olhos verdes.",
                        "Confira a cor dos olhos.", false),
                new Item(null, null, "O capítulo inteiro ignora o bilhete das notas.", "Veja se o bilhete aparece.",
                        false));
        ArgumentCaptor<IdeaModel.Prompt> prompt = ArgumentCaptor.forClass(IdeaModel.Prompt.class);
        verify(model).findings(prompt.capture());
        assertThat(prompt.getValue().user())
                .contains("Sinopse do livro:\nLia herda um café que guarda memórias.")
                .contains("Notas do autor (planos e lembretes):\n- Cap. 2: Tomás esconde o bilhete")
                .contains("Final do capítulo anterior:\nLia herdou o café da avó.\n\nTomás quer o café.")
                .contains("Capítulos anteriores, com o começo de cada um:\n- 1. A chegada")
                .doesNotContain("- 2. A carta")
                .contains("Personagens (fichas):\n- Tomás (antagonista) — aparência: Olhos verdes");
        ArgumentCaptor<SuggestionLog> log = ArgumentCaptor.forClass(SuggestionLog.class);
        verify(repository).save(log.capture());
        assertThat(log.getValue().getKind()).isEqualTo("review");
        assertThat(log.getValue().getField()).isEqualTo("continuity");
        assertThat(log.getValue().getIdeaCount()).isEqualTo(2);
    }

    @Test
    void aCorrectionOfJustOneWordBecomesTheWholeQuoteOrIsDropped() {
        assertThat(ReviewService.fullCorrection("Ela nao respondeu", "não")).isEqualTo("Ela não respondeu");
        assertThat(ReviewService.fullCorrection("no bolço.", "bolso")).isEqualTo("no bolso.");
        assertThat(ReviewService.fullCorrection("as janela", "as janelas")).isEqualTo("as janelas");
        // sem palavra parecida, aplicar apagaria texto: cai
        assertThat(ReviewService.fullCorrection("abriu as janela", "porta")).isEmpty();
        assertThat(ReviewService.fullCorrection("abriu as janela", "as portas")).isEmpty();
        // a "correção" igual à palavra que já está lá vira o mesmo trecho e é descartada pelo serviço
        assertThat(ReviewService.fullCorrection("abriu as janela", "janela")).isEqualTo("abriu as janela");
        // a frase inteira para um trecho curto: vale só o pedaço que corresponde, senão o texto duplicaria
        String sentence = "Ela não respondeu. Guardou a carta no bolso e serviu o café.";
        assertThat(ReviewService.fullCorrection("Ela nao respondeu", sentence)).isEqualTo("Ela não respondeu");
        assertThat(ReviewService.fullCorrection("bolço", sentence)).isEqualTo("bolso");
        assertThat(ReviewService.fullCorrection("o café.", sentence)).isEqualTo("o café.");
        assertThat(ReviewService.fullCorrection("abriu a janela", sentence)).isEmpty();
        assertThat(ReviewService.distance("nao", "nao")).isZero();
        assertThat(ReviewService.distance("bolco", "bolso")).isEqualTo(1);
    }

    @Test
    void spellingNeverOffersACorrectionThatWouldEraseText() {
        when(model.findings(any())).thenReturn(List.of(
                new Finding("viu as xícara", "Concordância", "xícaras"),
                new Finding("e mentia muito", "Grafia", "nada")));

        var result = service.review(owner, new ReviewService.Request(book, chapter2, "spelling", null, run));

        assertThat(result.findings()).containsExactly(new Item("b1", "viu as xícara", "Concordância", "viu as xícaras", true));
    }

    @Test
    void longChaptersGoInPartsWithoutBreakingParagraphs() {
        List<Block> blocks = List.of(new Block("p1", "a".repeat(3_000)), new Block("p2", "b".repeat(1_000)),
                new Block("p3", " "), new Block("p4", "c".repeat(2_000)));

        var parts = ReviewService.parts(blocks, 3_500);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).extracting(Block::blockId).containsExactly("p1");
        assertThat(parts.get(1)).extracting(Block::blockId).containsExactly("p2", "p4");
    }

    @Test
    void wrongCheckOtherChapterOrEmptyChapterAreRejected() {
        assertThatThrownBy(() -> service.review(owner, new ReviewService.Request(book, chapter2, "poetry", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("review_invalid"));
        assertThatThrownBy(() -> service.review(owner, new ReviewService.Request(book, UUID.randomUUID(), "spelling", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("chapter_not_found"));
        assertThatThrownBy(() -> service.review(owner, new ReviewService.Request(book, chapter2, "spelling", "zzz", run)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("review_part_invalid"));
        verify(model, never()).findings(any());
    }

    @Test
    void formatUsesRulesWithoutTheModelOrTheQuota() {
        var result = service.review(owner, new ReviewService.Request(book, chapter2, "format", null));

        assertThat(result.provider()).isEqualTo("regras");
        assertThat(result.parts()).isEqualTo(1);
        assertThat(result.nextBlock()).isNull();
        verify(model, never()).findings(any());
        verify(repository, never()).save(any());
    }

    @Test
    void styleKeepsOnlyRewritesOfPassagesThatExist() {
        when(model.findings(any())).thenReturn(List.of(
                new Finding("mentia muito", "Advérbio que sobra", "mentia"),
                new Finding("uma frase que não está no texto", "Redundância", "outra frase"),
                new Finding("viu as xícara", "Igual", "viu as xícara")));

        var result = service.review(owner, new ReviewService.Request(book, chapter2, "style", null, run));

        assertThat(result.findings()).containsExactly(new Item("b3", "mentia muito", "Advérbio que sobra", "mentia", true));
        ArgumentCaptor<IdeaModel.Prompt> prompt = ArgumentCaptor.forClass(IdeaModel.Prompt.class);
        verify(model).findings(prompt.capture());
        assertThat(prompt.getValue().system()).contains("frases e estilo");
        assertThat(prompt.getValue().user()).contains("Nomes próprios do universo").doesNotContain("Sinopse");
    }

    @Test
    void startingAReviewSpendsOneUseAndBoundsTheCalls() {
        when(runs.save(any())).thenAnswer(call -> {
            ReviewRun saved = call.getArgument(0);
            return ReviewRun.builder().id(run).userId(saved.getUserId()).chapterId(saved.getChapterId())
                    .maxCalls(saved.getMaxCalls()).build();
        });

        var started = service.start(owner, new ReviewService.StartRequest(book, chapter2));

        assertThat(started.runId()).isEqualTo(run);
        ArgumentCaptor<ReviewRun> saved = ArgumentCaptor.forClass(ReviewRun.class);
        verify(runs).save(saved.capture());
        // capítulo curto: uma parte em cada uma das cinco revisões com IA, mais a folga para repetir
        assertThat(saved.getValue().getMaxCalls()).isEqualTo(5 + ReviewService.RETRY_CALLS);
        assertThat(saved.getValue().getChapterId()).isEqualTo(chapter2);
        verify(model, never()).findings(any());
    }

    @Test
    void anAiCheckNeedsAnOpenReview() {
        assertThatThrownBy(() -> service.review(owner, new ReviewService.Request(book, chapter2, "spelling", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("review_run_invalid"));
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> service.review(owner,
                new ReviewService.Request(book, chapter2, "spelling", null, other)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("review_run_invalid"));
        verify(model, never()).findings(any());
        // a formatação não precisa de revisão aberta
        assertThat(service.review(owner, new ReviewService.Request(book, chapter2, "format", null)).provider())
                .isEqualTo("regras");
    }
}
