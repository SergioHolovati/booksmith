package com.booksmith.assist.internal;

import com.booksmith.assist.internal.IdeaModel.Finding;
import com.booksmith.assist.internal.Prompts.Section;
import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.BookText;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;
import com.booksmith.universe.api.Universes;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Revisão do capítulo pela IA (§4.11 do produto): ortografia e gramática, furos de roteiro, erros de
 * continuidade e sugestões de melhoria no enredo. A IA aponta, o autor decide: nada muda no texto sozinho.
 * <p>
 * O capítulo vai em partes, para caber no modelo local: o app pede a primeira, recebe {@code nextBlock} e pede a
 * seguinte, mostrando o andamento. Cada apontamento é conferido: o trecho citado precisa existir no capítulo
 * (senão, na ortografia, é descartado; nos outros, fica sem trecho), e ganha o id do bloco onde está.
 */
@Service
public class ReviewService {

    /** As revisões e o nome de cada uma no registro de uso. */
    static final Map<String, String> CHECKS = Map.of(
            "spelling", "ortografia", "holes", "furos de roteiro", "continuity", "continuidade",
            "improvements", "melhorias no enredo");
    /** Tamanho de cada parte do capítulo: a ortografia olha de perto, as outras precisam de mais texto. */
    static final int SPELLING_PART = 3_500;
    static final int STORY_PART = 9_000;
    /** Material de contexto em cada pedido (cabe com o trecho no contexto do modelo local). */
    static final int SPELLING_MATERIAL = 2_000;
    static final int STORY_MATERIAL = 9_000;
    static final int PREVIOUS_TAIL = 2_000;
    static final int MAX_FINDINGS = 15;
    static final int MAX_QUOTE = 300;
    static final int MAX_PROBLEM = 500;
    static final int MAX_SUGGESTION = 600;

    private final AiUsage usage;
    private final Manuscripts manuscripts;
    private final Universes universes;

    public ReviewService(AiUsage usage, Manuscripts manuscripts, Universes universes) {
        this.usage = usage;
        this.manuscripts = manuscripts;
        this.universes = universes;
    }

    /**
     * @param check     {@code spelling}, {@code holes}, {@code continuity} ou {@code improvements}
     * @param fromBlock o bloco onde começa a parte pedida ({@code nextBlock} da resposta anterior); vazio = a
     *                  primeira
     */
    public record Request(UUID bookId, UUID chapterId, String check, String fromBlock) {
    }

    /** @param blockId o bloco do capítulo onde está o trecho, ou null se o apontamento é do capítulo todo */
    public record Item(String blockId, String quote, String problem, String suggestion) {
    }

    /** @param nextBlock onde começa a próxima parte; null quando o capítulo acabou */
    public record Result(List<Item> findings, String nextBlock, int part, int parts, int remaining, String provider,
                         String model, boolean local) {
    }

    public Result review(UUID ownerId, Request request) {
        String check = request.check();
        if (check == null || !CHECKS.containsKey(check)) {
            throw invalid("Tipo de revisão desconhecido.");
        }
        if (request.bookId() == null || request.chapterId() == null) {
            throw invalid("Diga qual capítulo revisar.");
        }
        usage.requireAvailable(ownerId);
        BookText book = manuscripts.bookText(ownerId, request.bookId());
        ChapterText chapter = book.chapters().stream()
                .filter(c -> c.chapterId().equals(request.chapterId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("chapter_not_found", "Capítulo não encontrado."));
        List<List<Block>> parts = parts(chapter.blocks(), "spelling".equals(check) ? SPELLING_PART : STORY_PART);
        if (parts.isEmpty()) {
            throw ApiException.unprocessable("chapter_empty", "O capítulo ainda não tem texto para revisar.");
        }
        int index = partIndex(parts, request.fromBlock());
        List<Block> part = parts.get(index);

        String label = "o capítulo " + chapter.position()
                + (chapter.title() == null || chapter.title().isBlank() ? "" : " (\"" + chapter.title() + "\")")
                + (parts.size() > 1 ? ", parte " + (index + 1) + " de " + parts.size() : "");
        IdeaModel.Prompt prompt = "spelling".equals(check)
                ? Prompts.review(check, label, spellingMaterial(book), SPELLING_MATERIAL, render(part))
                : Prompts.review(check, label, storyMaterial(ownerId, request.bookId(), book, chapter, check),
                STORY_MATERIAL, render(part));

        IdeaModel model = usage.model();
        List<Item> items = usage.run(ownerId, "review", check, "review",
                () -> locate(model.findings(prompt), part, "spelling".equals(check)), List::size);
        String next = index + 1 < parts.size() ? parts.get(index + 1).getFirst().blockId() : null;
        return new Result(items, next, index + 1, parts.size(), usage.remaining(ownerId), model.provider(),
                model.model(), model.local());
    }

    /** Ortografia: só os nomes próprios do universo, para a IA não marcar "Tomás" ou "Vel" como erro. */
    private List<Section> spellingMaterial(BookText book) {
        if (book.universeId() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(universes.vocabulary(book.universeId()));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.of(Section.of("Nomes próprios do universo (não são erro)", String.join(", ", names)));
    }

    /** Furos, continuidade e melhorias: o que já foi contado, os planos do autor e as fichas. */
    private List<Section> storyMaterial(UUID ownerId, UUID bookId, BookText book, ChapterText chapter, String check) {
        var content = manuscripts.bookContent(ownerId, bookId);
        List<ChapterText> before = book.chapters().stream().filter(c -> c.position() < chapter.position()).toList();
        List<Section> material = new ArrayList<>();
        material.add(Section.of("Sinopse do livro", content.synopsis()));
        material.add(Section.list("Notas do autor (planos e lembretes)", manuscripts.openNotes(ownerId, bookId)));
        if (!before.isEmpty()) {
            material.add(Section.of("Final do capítulo anterior", tail(before.getLast())));
        }
        material.add(Section.list("improvements".equals(check) ? "Todos os capítulos, com o começo de cada um"
                : "Capítulos anteriores, com o começo de cada um", ("improvements".equals(check) ? book.chapters() : before)
                .stream().map(AssistService::chapterLine).toList()));
        if (book.universeId() != null) {
            Universes.Brief brief = universes.brief(book.universeId(), null);
            material.add(Section.list("Personagens (fichas)", brief.characters()));
            material.add(Section.list("Relações entre personagens", brief.relations()));
            material.add(Section.list("Lugares (fichas)", brief.places()));
            material.add(Section.list("Linha do tempo", brief.timeline()));
            material.add(Section.list("Universo \"" + brief.name() + "\"", brief.lines()));
            material.add(Section.list("Fichas do mundo", brief.lore()));
        }
        return material;
    }

    /** Divide os blocos com texto em partes de até {@code size} caracteres, sem quebrar parágrafo. */
    static List<List<Block>> parts(List<Block> blocks, int size) {
        List<List<Block>> parts = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        int length = 0;
        for (Block block : blocks) {
            if (block.text() == null || block.text().isBlank()) {
                continue;
            }
            if (!current.isEmpty() && length + block.text().length() > size) {
                parts.add(current);
                current = new ArrayList<>();
                length = 0;
            }
            current.add(block);
            length += block.text().length() + 2;
        }
        if (!current.isEmpty()) {
            parts.add(current);
        }
        return parts;
    }

    private static int partIndex(List<List<Block>> parts, String fromBlock) {
        if (fromBlock == null || fromBlock.isBlank()) {
            return 0;
        }
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).getFirst().blockId().equals(fromBlock)) {
                return i;
            }
        }
        throw ApiException.unprocessable("review_part_invalid",
                "O capítulo mudou durante a revisão. Comece de novo.");
    }

    private static String render(List<Block> part) {
        return String.join("\n\n", part.stream().map(Block::text).toList());
    }

    private static String tail(ChapterText chapter) {
        String body = render(chapter.blocks().stream().filter(b -> b.text() != null && !b.text().isBlank()).toList());
        return body.length() > PREVIOUS_TAIL ? "…" + body.substring(body.length() - PREVIOUS_TAIL) : body;
    }

    /**
     * Confere cada apontamento contra o trecho revisado: acha o bloco do trecho citado (exato, ou sem diferença de
     * maiúsculas) e usa o texto como está no capítulo. Na ortografia, sem trecho achado ou sem correção diferente,
     * o apontamento cai; nos outros, fica sem trecho. Repetidos caem.
     */
    static List<Item> locate(List<Finding> findings, List<Block> part, boolean spelling) {
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (findings == null) {
            return items;
        }
        for (Finding finding : findings) {
            if (finding == null || items.size() >= MAX_FINDINGS) {
                continue;
            }
            String problem = clip(finding.problem(), MAX_PROBLEM);
            String suggestion = clip(finding.suggestion(), MAX_SUGGESTION);
            String quote = clip(finding.quote(), MAX_QUOTE);
            Item placed = place(quote, part);
            if (spelling) {
                suggestion = placed == null ? "" : fullCorrection(placed.quote(), suggestion);
                if (suggestion.isEmpty() || suggestion.equals(placed.quote())) {
                    continue;
                }
            }
            if (problem.isEmpty() && suggestion.isEmpty()) {
                continue;
            }
            String foundQuote = placed == null ? null : placed.quote();
            if (!seen.add(foundQuote + "|" + problem)) {
                continue;
            }
            items.add(new Item(placed == null ? null : placed.blockId(), foundQuote, problem, suggestion));
        }
        return items;
    }

    /**
     * A correção precisa substituir o trecho inteiro ("Corrigir" troca quote por ela). Modelos pequenos às vezes
     * devolvem só a palavra corrigida ("nao respondeu" → "não"): aí a palavra entra no lugar da mais parecida do
     * trecho ("não respondeu"). Sem palavra parecida, a correção é descartada (vazio): aplicar seria apagar texto.
     */
    static String fullCorrection(String quote, String suggestion) {
        String[] quoteWords = quote.split("\\s+");
        String[] fixWords = suggestion.split("\\s+");
        if (suggestion.isBlank() || fixWords.length >= quoteWords.length) {
            return suggestion;
        }
        if (fixWords.length != 1) {
            return "";
        }
        String fix = fixWords[0];
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < quoteWords.length; i++) {
            int distance = distance(plain(core(quoteWords[i])), plain(core(fix)));
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        String word = core(quoteWords[best]);
        if (bestDistance > Math.max(2, word.length() / 2)) {
            return "";
        }
        quoteWords[best] = quoteWords[best].replace(word, core(fix));
        return String.join(" ", quoteWords);
    }

    /** A palavra sem a pontuação em volta ("bolço." → "bolço"). */
    private static String core(String word) {
        return word.replaceAll("^[\\p{Punct}«»“”…]+|[\\p{Punct}«»“”…]+$", "");
    }

    /** Sem acento e em minúsculas, para comparar "nao" com "não". */
    private static String plain(String word) {
        return java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Distância de edição (Levenshtein) entre duas palavras curtas. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** O bloco onde o trecho está e o trecho como está escrito lá; null se não achou. */
    private static Item place(String quote, List<Block> part) {
        if (quote.isEmpty()) {
            return null;
        }
        for (Block block : part) {
            if (block.text().contains(quote)) {
                return new Item(block.blockId(), quote, null, null);
            }
        }
        String lower = quote.toLowerCase(Locale.ROOT);
        for (Block block : part) {
            int at = block.text().toLowerCase(Locale.ROOT).indexOf(lower);
            if (at >= 0) {
                return new Item(block.blockId(), block.text().substring(at, at + quote.length()), null, null);
            }
        }
        return null;
    }

    private static String clip(String text, int max) {
        String value = text == null ? "" : text.strip();
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("review_invalid", message);
    }
}
