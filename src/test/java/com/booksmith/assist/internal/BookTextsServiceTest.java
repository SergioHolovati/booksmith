package com.booksmith.assist.internal;

import com.booksmith.assist.internal.BookTextsService.Item;
import com.booksmith.assist.internal.BookTextsService.Text;
import com.booksmith.assist.internal.IdeaModel.Finding;
import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;
import com.booksmith.universe.api.Universes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookTextsServiceTest {

    private final IdeaModel model = mock(IdeaModel.class);
    private final SuggestionLogRepository repository = mock(SuggestionLogRepository.class);
    private final ReviewRunRepository runs = mock(ReviewRunRepository.class);
    private final Manuscripts manuscripts = mock(Manuscripts.class);
    private final Universes universes = mock(Universes.class);
    private final AiProperties properties = new AiProperties("anthropic", 3, 1,
            new AiProperties.Anthropic("", "claude-haiku-5-5"));
    private final BookTextsService service = new BookTextsService(
            new AiUsage(model, properties, repository, runs, Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC)),
            manuscripts, universes);

    private final UUID owner = UUID.randomUUID();
    private final UUID book = UUID.randomUUID();
    private final UUID universe = UUID.randomUUID();
    private final UUID sergio = UUID.randomUUID();

    private final List<ChapterText> chapters = List.of(new ChapterText(UUID.randomUUID(), 1, "A fuga", List.of(
            new Block("a1", "Naquela noite, eu, Sérgio, peguei o Ben no colo."),
            new Block("a2", "A Giulia olhou para o Sérgio e disse que o Ben tinha cinco meses."),
            new Block("a3", "Está tudo bem, pensou o Sérgio."))));

    BookTextsServiceTest() {
        when(model.provider()).thenReturn("anthropic");
        when(model.model()).thenReturn("claude-haiku-5-5");
        when(manuscripts.bookText(owner, book)).thenReturn(new Manuscripts.BookText(book, "A conversão", universe,
                chapters));
        Manuscripts.BookPages pages = new Manuscripts.BookPages(null, null, null, null, null,
                "Sergio mora em São Paulo  e escreve à noite .", null);
        when(manuscripts.bookContent(owner, book)).thenReturn(new Manuscripts.BookContent(book, "A conversão",
                "Esta é a história de Sergio, que protege o filho de 6 meses.", "pt-BR", false, null, null, universe,
                List.of(), pages, null));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("personality", "teimoso,amoroso");
        when(universes.characterSheets(universe)).thenReturn(List.of(
                new Universes.CharacterSheet(sergio, "Sergio Henrique", List.of("Serjo"), fields)));
        when(manuscripts.proofread(eq(owner), eq(book), anyMap())).thenReturn(Map.of());
    }

    @Test
    void namesThatDivergeFromTheStoryInEveryText() {
        List<Item> names = service.check(owner, book).items().stream().filter(i -> "name".equals(i.kind())).toList();

        assertThat(names).extracting(Item::label, Item::quote, Item::suggestion).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Sinopse", "Sergio", "Sérgio"),
                org.assertj.core.groups.Tuple.tuple("Sobre o autor", "Sergio", "Sérgio"),
                org.assertj.core.groups.Tuple.tuple("Ficha de Sergio Henrique · Nome", "Sergio", "Sérgio"));
        assertThat(names.getFirst().problem()).contains("\"Sérgio\" (3 vezes)");
        assertThat(names.get(2).source()).isEqualTo("character");
        assertThat(names.get(2).id()).isEqualTo(sergio);
    }

    @Test
    void aCapitalAtTheStartOfASentenceIsNotAName() {
        // "Está" só aparece no começo da frase na história: "Esta" na sinopse não é divergência
        assertThat(BookTextsService.properNames(chapters, java.util.Set.of())).containsKey("sergio").doesNotContainKey("esta");
        assertThat(service.check(owner, book).items()).noneMatch(i -> "Esta".equals(i.quote()));
    }

    @Test
    void aSheetNameCountsEvenAtTheStartOfASpeech() {
        List<ChapterText> story = List.of(new ChapterText(UUID.randomUUID(), 3, "A cura", List.of(
                new Block("a", "— Sérgio, olha suas costas! — gritou Ana."),
                new Block("b", "— Sérgio, a ferida fechou."))));
        Text name = new Text("c:x:name", "character", sergio, "Ficha de Sergio · Nome", "Sergio Henrique");

        assertThat(BookTextsService.names(List.of(name), story)).singleElement()
                .satisfies(item -> assertThat(item.problem()).contains("\"Sérgio\" (2 vezes)"));
    }

    @Test
    void anAliasOneLetterAwayFromAStoryName() {
        List<ChapterText> story = List.of(new ChapterText(UUID.randomUUID(), 1, "Um", List.of(
                new Block("a", "Então o Kaelor saiu, e o Kaelor voltou, e o Kaelor riu."))));
        Text alias = new Text("c:x:aliases", "character", UUID.randomUUID(), "Ficha de K · Apelidos", "Kaellor");

        assertThat(BookTextsService.names(List.of(alias), story)).singleElement()
                .satisfies(item -> assertThat(item.suggestion()).isEqualTo("Kaelor"));
    }

    @Test
    void formattingInTheTexts() {
        List<Item> format = service.check(owner, book).items().stream()
                .filter(i -> "format".equals(i.kind())).toList();

        assertThat(format).extracting(Item::label, Item::problem).contains(
                org.assertj.core.groups.Tuple.tuple("Sobre o autor", "Espaço duplo."),
                org.assertj.core.groups.Tuple.tuple("Sobre o autor", "Espaço antes de \".\"."),
                org.assertj.core.groups.Tuple.tuple("Ficha de Sergio Henrique · Personalidade",
                        "Falta espaço depois de \",\"."));
        Text lower = new Text("synopsis", "synopsis", null, "Sinopse", "Ela fugiu. depois voltou.");
        assertThat(BookTextsService.format(lower)).singleElement()
                .satisfies(i -> assertThat(i.suggestion()).isEqualTo("fugiu. D"));
    }

    @Test
    void spellingComesFromTheBooksmithCorrectorButNotForNames() {
        when(manuscripts.proofread(eq(owner), eq(book), anyMap())).thenReturn(Map.of("synopsis", List.of(
                new Manuscripts.TextIssue(0, 4, "spelling", "Erro de grafia", List.of("Essa")),
                new Manuscripts.TextIssue(5, 1, "style", "Estilo", List.of()))));

        List<Item> items = service.check(owner, book).items().stream()
                .filter(i -> "spelling".equals(i.kind())).toList();

        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.quote()).isEqualTo("Esta");
            assertThat(i.suggestion()).isEqualTo("Essa");
            assertThat(i.context()).startsWith("Esta é a história");
        });
        // o nome e os apelidos não passam pelo corretor (são nomes inventados)
        verify(manuscripts).proofread(eq(owner), eq(book), org.mockito.ArgumentMatchers.argThat(
                (Map<String, String> m) -> m.keySet().stream().noneMatch(k -> k.endsWith(":name") || k.endsWith(":aliases"))));
        verify(model, never()).findings(any());
    }

    @Test
    void factsFromTheAiArePlacedInTheirText() {
        when(model.findings(any())).thenReturn(List.of(
                new Finding("filho de 6 meses", "A história diz 5 meses (cap. 1).", "Confira a idade do Ben."),
                new Finding("trecho que não existe", "x", "y")));

        var result = service.compare(owner, book);

        assertThat(result.items()).singleElement().satisfies(i -> {
            assertThat(i.label()).isEqualTo("Sinopse");
            assertThat(i.kind()).isEqualTo("fact");
            assertThat(i.problem()).contains("5 meses");
        });
    }

    @Test
    void applyingFixesChecksEachPassageAndKeepsPositions() {
        String text = "Sergio, pai de familia , protege a familia. Sergio corre.";
        var fixes = List.of(
                new BookTextsService.Fix("synopsis", text.indexOf("familia"), "familia", "família", false),
                new BookTextsService.Fix("synopsis", text.indexOf(" ,"), " ,", ",", false),
                new BookTextsService.Fix("synopsis", text.lastIndexOf("familia"), "familia", "família", false),
                new BookTextsService.Fix("synopsis", 0, "Sergio", "Sérgio", true),
                // o autor já tinha mudado este ponto: pula
                new BookTextsService.Fix("synopsis", 3, "xyz", "abc", false));

        var result = BookTextsService.applyTo(text, fixes);

        assertThat(result.text()).isEqualTo("Sérgio, pai de família, protege a família. Sérgio corre.");
        assertThat(result.applied()).isEqualTo(4);
    }

    @Test
    void applySavesEachChangedText() {
        var result = service.apply(owner, book, List.of(
                new BookTextsService.Fix("synopsis", 0, "Esta", "Essa", false),
                new BookTextsService.Fix("about", 0, "Sergio", "Sérgio", true),
                new BookTextsService.Fix("c:" + sergio + ":name", 0, "Sergio", "Sérgio", true),
                new BookTextsService.Fix("c:" + sergio + ":personality", 7, ",", ", ", false),
                new BookTextsService.Fix("texto-que-nao-existe", 0, "a", "b", false)));

        assertThat(result.applied()).isEqualTo(4);
        assertThat(result.skipped()).isEqualTo(1);
        verify(manuscripts).replaceSynopsis(owner, book,
                "Essa é a história de Sergio, que protege o filho de 6 meses.");
        verify(manuscripts).replaceAboutAuthor(owner, book, "Sérgio mora em São Paulo  e escreve à noite .");
        verify(universes).replaceCharacterText(owner, sergio, "name", "Sérgio Henrique");
        verify(universes).replaceCharacterText(owner, sergio, "personality", "teimoso, amoroso");
    }

    @Test
    void factsFromTheAiOnlyPoint() {
        when(model.findings(any())).thenReturn(List.of(new Finding("filho de 6 meses", "Diverge", "Confira")));
        assertThat(service.compare(owner, book).items()).allSatisfy(i -> {
            assertThat(i.fix()).isFalse();
            assertThat(i.key()).isEqualTo("synopsis");
        });
    }

    @Test
    void comparingNeedsTheAiAndAUse() {
        when(repository.countByUserIdAndKindNotAndCreatedAtAfter(eq(owner), eq("review"), any())).thenReturn(3L);
        assertThatThrownBy(() -> service.compare(owner, book))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_daily_limit"));
        verify(model, never()).findings(any());
    }
}
