package com.booksmith.assist.internal;

import com.booksmith.assist.internal.IdeaModel.Idea;
import com.booksmith.assist.internal.Prompts.Section;
import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.universe.api.Universes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Sugestões de IA (§4.11 do produto) para um campo do universo, da ficha do personagem ou para o enredo do livro.
 * Junta o contexto (o que está na tela, mandado pelo app, mais o resumo guardado do universo ou do livro), pede ao
 * {@link IdeaModel} e devolve ideias curtas, sem guardar texto nenhum. Limite por pessoa em 24 horas; o modelo
 * local atende um pedido por vez, e quem chega depois espera na fila.
 */
@Slf4j
@Service
public class AssistService {

    static final Set<String> KINDS = Set.of("universe", "character", "plot");
    static final Set<String> MODES = Set.of("ideas", "questions");
    /** Enredo: o que o autor pode pedir, com o texto do pedido. */
    static final Map<String, String> PLOT_FIELDS = Map.of(
            "next", "do que pode acontecer depois, a partir de onde a história está",
            "conflict", "de conflitos e obstáculos para os personagens",
            "twist", "de reviravoltas que façam sentido com o que já foi contado",
            "subplot", "de tramas paralelas que enriqueçam a história principal",
            "ending", "de caminhos possíveis para o final");
    static final int MAX_LABEL = 80;
    static final int MAX_FIELD = 40;
    static final int MAX_HINT = 300;
    static final int MAX_CURRENT = 4_000;
    static final int MAX_CONTEXT_LINES = 40;
    static final int MAX_CONTEXT_TEXT = 2_000;
    /** Trecho final do capítulo mais recente que vai como contexto do enredo. */
    static final int CHAPTER_TAIL = 3_000;
    /** Começo de cada capítulo no contexto do enredo: dá à IA o fio da história inteira. */
    static final int CHAPTER_OPENING = 220;
    static final int MAX_NOTES = 30;
    static final int MAX_BOOKS = 10;
    static final int MAX_IDEAS = 6;
    static final int MAX_TITLE = 140;
    static final int MAX_TEXT = 600;
    /** Quanto um pedido espera na fila antes de desistir. */
    static final Duration QUEUE_WAIT = Duration.ofMinutes(2);

    private final IdeaModel model;
    private final AiProperties properties;
    private final SuggestionLogRepository repository;
    private final Universes universes;
    private final Manuscripts manuscripts;
    private final Clock clock;
    private final Semaphore gate;

    public AssistService(IdeaModel model, AiProperties properties, SuggestionLogRepository repository,
                         Universes universes, Manuscripts manuscripts, Clock clock) {
        this.model = model;
        this.properties = properties;
        this.repository = repository;
        this.universes = universes;
        this.manuscripts = manuscripts;
        this.clock = clock;
        this.gate = new Semaphore(Math.max(1, properties.concurrency()), true);
    }

    /** Uma linha do que está na tela: rótulo e valor ("Personalidade: curiosa e teimosa"). */
    public record ContextLine(String label, String text) {
    }

    /**
     * @param kind       {@code universe}, {@code character} ou {@code plot}
     * @param mode       {@code ideas} (padrão) ou {@code questions}
     * @param field      chave do campo (no enredo, uma de {@link #PLOT_FIELDS})
     * @param fieldLabel rótulo do campo na tela (universo e personagem)
     * @param current    o que já está escrito no campo, mesmo sem salvar
     * @param context    o resto da ficha na tela, mesmo sem salvar
     * @param hint       pedido extra do autor ("algo mais sombrio")
     * @param characterId o personagem da ficha aberta: as relações dele vêm primeiro no contexto
     * @param fieldHelp  o que o campo pede, como a tela explica ("Do que tem medo, o que evita")
     */
    public record Request(String kind, String mode, UUID universeId, UUID bookId, String field, String fieldLabel,
                          String current, List<ContextLine> context, String hint, UUID characterId,
                          String fieldHelp) {

        public Request(String kind, String mode, UUID universeId, UUID bookId, String field, String fieldLabel,
                       String current, List<ContextLine> context, String hint) {
            this(kind, mode, universeId, bookId, field, fieldLabel, current, context, hint, null, null);
        }
    }

    public record Result(List<Idea> suggestions, String provider, String model, boolean local, int remaining) {
    }

    public record Status(boolean enabled, String provider, String model, boolean local, int dailyLimit, int remaining) {
    }

    public Status status(UUID ownerId) {
        boolean enabled = !"off".equals(model.provider());
        return new Status(enabled, model.provider(), model.model(), model.local(), properties.dailyLimit(),
                enabled ? remaining(ownerId) : 0);
    }

    public Result suggest(UUID ownerId, Request request) {
        if ("off".equals(model.provider())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_disabled",
                    "As sugestões de IA estão desligadas neste servidor.");
        }
        String kind = require(request.kind(), KINDS, "Tipo de sugestão desconhecido.");
        String mode = request.mode() == null || request.mode().isBlank() ? "ideas"
                : require(request.mode(), MODES, "Modo de sugestão desconhecido.");
        String field = text(request.field(), MAX_FIELD, "o campo");
        if (field.isEmpty()) {
            throw invalid("Diga para qual campo são as sugestões.");
        }
        String hint = text(request.hint(), MAX_HINT, "o pedido extra");
        String current = text(request.current(), MAX_CURRENT, "o texto do campo");
        if (remaining(ownerId) <= 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ai_daily_limit",
                    "Você usou as %d sugestões de hoje. Amanhã tem mais.".formatted(properties.dailyLimit()));
        }
        IdeaModel.Prompt prompt = switch (kind) {
            case "plot" -> plotPrompt(ownerId, request, mode, field, hint);
            case "character" -> sheetPrompt(ownerId, request, mode, "do personagem", field, current, hint);
            default -> sheetPrompt(ownerId, request, mode, "do universo", field, current, hint);
        };

        long started = System.nanoTime();
        List<Idea> ideas = ask(prompt);
        int durationMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        repository.save(SuggestionLog.builder()
                .userId(ownerId).kind(kind).field(field).mode(mode)
                .provider(model.provider()).model(model.model())
                .durationMs(durationMs).ideaCount(ideas.size())
                .build());
        log.info("Sugestões {} {} {} em {} ms ({})", kind, field, mode, durationMs, model.model());
        return new Result(ideas, model.provider(), model.model(), model.local(), remaining(ownerId));
    }

    private List<Idea> ask(IdeaModel.Prompt prompt) {
        boolean acquired;
        try {
            acquired = gate.tryAcquire(QUEUE_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy().causedBy(e);
        }
        if (!acquired) {
            throw busy();
        }
        try {
            List<Idea> ideas = clean(model.ideas(prompt));
            if (ideas.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_bad_answer",
                        "A IA não trouxe nenhuma ideia desta vez. Tente de novo.");
            }
            return ideas;
        } finally {
            gate.release();
        }
    }

    private IdeaModel.Prompt sheetPrompt(UUID ownerId, Request request, String mode, String where, String field,
                                         String current, String hint) {
        if (request.universeId() == null) {
            throw invalid("Diga de qual universo é a ficha.");
        }
        universes.requireOwned(ownerId, request.universeId());
        String label = text(request.fieldLabel(), MAX_LABEL, "o nome do campo");
        String help = text(request.fieldHelp(), MAX_HINT, "a explicação do campo");
        String name = label.isEmpty() ? field : label;
        UUID focus = "do personagem".equals(where) ? request.characterId() : null;
        Universes.Brief brief = universes.brief(request.universeId(), focus);
        // Em ordem de importância: se o material passar do limite, o que corta é o fim
        List<Section> material = new ArrayList<>();
        material.add(Section.of("O que está escrito no campo \"" + name + "\"", current.isEmpty() ? "(vazio)" : current));
        material.add(Section.list("A ficha na tela", contextLines(request.context())));
        material.add(Section.list("Relações entre personagens", brief.relations()));
        material.add(booksSection(ownerId, request.universeId()));
        material.add(universeSection(brief));
        material.add(Section.list(focus == null ? "Personagens do universo" : "Outros personagens do universo",
                brief.characters()));
        material.add(Section.list("Lugares", brief.places()));
        material.add(Section.list("Fichas do mundo", brief.lore()));
        material.add(Section.list("Linha do tempo", brief.timeline()));
        String asked = "para o campo \"" + name + "\" " + where + (help.isEmpty() ? "" : " (" + help + ")");
        return Prompts.build(mode, asked, hint, material);
    }

    /** Os livros que se passam no universo, com a sinopse: dizem que história as fichas servem. */
    private Section booksSection(UUID ownerId, UUID universeId) {
        List<String> books = manuscripts.booksInUniverse(ownerId, universeId).stream()
                .limit(MAX_BOOKS)
                .map(b -> "\"" + b.title() + "\"" + (b.synopsis() == null || b.synopsis().isBlank() ? ""
                        : ": " + b.synopsis().replaceAll("\\s+", " ").strip()))
                .toList();
        return Section.list("Livros que se passam neste universo", books);
    }

    private IdeaModel.Prompt plotPrompt(UUID ownerId, Request request, String mode, String field, String hint) {
        String what = PLOT_FIELDS.get(field);
        if (what == null) {
            throw invalid("Parte do enredo desconhecida.");
        }
        if (request.bookId() == null) {
            throw invalid("Diga de qual livro é o enredo.");
        }
        var content = manuscripts.bookContent(ownerId, request.bookId());
        var text = manuscripts.bookText(ownerId, request.bookId());
        // Em ordem de importância: se o material passar do limite, o que corta é o fim
        List<Section> material = new ArrayList<>();
        material.add(Section.of("Livro", "\"" + content.title() + "\""));
        material.add(Section.of("Sinopse", content.synopsis()));
        material.add(Section.list("Notas do autor (planos e lembretes)",
                manuscripts.openNotes(ownerId, request.bookId()).stream().limit(MAX_NOTES).toList()));
        material.add(Section.list("Capítulos até agora, com o começo de cada um", text.chapters().stream()
                .map(AssistService::chapterLine).toList()));
        latestTail(text).ifPresent(material::add);
        if (content.universeId() != null) {
            Universes.Brief brief = universes.brief(content.universeId(), null);
            material.add(Section.list("Personagens", brief.characters()));
            material.add(Section.list("Relações entre personagens", brief.relations()));
            material.add(universeSection(brief));
            material.add(Section.list("Lugares", brief.places()));
            material.add(Section.list("Fichas do mundo", brief.lore()));
            material.add(Section.list("Linha do tempo", brief.timeline()));
        }
        String asked = "questions".equals(mode) ? "sobre o enredo do livro, a respeito " + what
                : "para o enredo do livro, " + what;
        return Prompts.build(mode, asked, hint, material);
    }

    /** "2. A carta — começa: Lia achou o bilhete…": o título e o começo do capítulo. */
    static String chapterLine(Manuscripts.ChapterText chapter) {
        String title = chapter.title() == null || chapter.title().isBlank() ? "(sem título)" : chapter.title();
        String body = String.join(" ", chapter.blocks().stream()
                .map(Manuscripts.Block::text).filter(t -> t != null && !t.isBlank()).toList())
                .replaceAll("\\s+", " ").strip();
        if (body.isEmpty()) {
            return chapter.position() + ". " + title;
        }
        String opening = body.length() > CHAPTER_OPENING ? body.substring(0, CHAPTER_OPENING) + "…" : body;
        return chapter.position() + ". " + title + " — começa: " + opening;
    }

    /** O fim do capítulo mais recente que tem texto: é dali que a história continua. */
    private static java.util.Optional<Section> latestTail(Manuscripts.BookText text) {
        for (var chapter : text.chapters().reversed()) {
            String body = String.join("\n", chapter.blocks().stream()
                    .map(Manuscripts.Block::text).filter(t -> t != null && !t.isBlank()).toList());
            if (!body.isBlank()) {
                String tail = body.length() > CHAPTER_TAIL ? "…" + body.substring(body.length() - CHAPTER_TAIL) : body;
                return java.util.Optional.of(Section.of("Final do capítulo " + chapter.position()
                        + " (o mais recente com texto)", tail));
            }
        }
        return java.util.Optional.empty();
    }

    private static Section universeSection(Universes.Brief brief) {
        return Section.list("Universo \"" + brief.name() + "\"", brief.lines());
    }

    private static List<String> contextLines(List<ContextLine> context) {
        if (context == null) {
            return List.of();
        }
        if (context.size() > MAX_CONTEXT_LINES) {
            throw invalid("Contexto grande demais.");
        }
        List<String> lines = new ArrayList<>();
        for (ContextLine line : context) {
            if (line == null) {
                continue;
            }
            String label = text(line.label(), MAX_LABEL, "o rótulo do contexto");
            String value = text(line.text(), MAX_CONTEXT_TEXT, "o contexto");
            if (!value.isEmpty()) {
                lines.add("- " + (label.isEmpty() ? "" : label + ": ") + value);
            }
        }
        return lines;
    }

    /** Ideias limpas: sem vazias, cortadas no tamanho, no máximo {@link #MAX_IDEAS}. */
    static List<Idea> clean(List<Idea> ideas) {
        if (ideas == null) {
            return List.of();
        }
        return ideas.stream()
                .filter(i -> i != null && i.text() != null && !i.text().isBlank())
                .map(i -> new Idea(cut(i.title() == null ? "" : i.title().strip(), MAX_TITLE), cut(i.text().strip(), MAX_TEXT)))
                .limit(MAX_IDEAS)
                .toList();
    }

    private int remaining(UUID ownerId) {
        Instant dayAgo = Instant.now(clock).minus(Duration.ofDays(1));
        long used = repository.countByUserIdAndCreatedAtAfter(ownerId, dayAgo);
        return (int) Math.max(0, properties.dailyLimit() - used);
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    private static String text(String value, int max, String what) {
        String text = value == null ? "" : value.strip();
        if (text.length() > max) {
            throw invalid("Texto longo demais em %s (até %d caracteres).".formatted(what, max));
        }
        return text;
    }

    private static String require(String value, Set<String> allowed, String message) {
        if (value == null || !allowed.contains(value)) {
            throw invalid(message);
        }
        return value;
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("suggestion_invalid", message);
    }

    private static ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_busy",
                "A IA está ocupada com outros pedidos. Tente de novo em um minuto.");
    }
}
