package com.booksmith.assist.internal;

import com.booksmith.assist.internal.IdeaModel.Answer;
import com.booksmith.assist.internal.IdeaModel.Source;
import com.booksmith.assist.internal.Prompts.Section;
import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.BookText;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;
import com.booksmith.universe.api.Universes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Pergunte ao seu livro" (§4.5): o autor pergunta ("Quantos anos o Ben tem?", "Quem sabe da carta?") e a IA
 * responde com o que está no texto e nas fichas, mostrando os trechos de origem. Cada trecho citado é conferido
 * contra o livro (senão cai) e ganha o capítulo e o parágrafo, para o app abrir o editor no lugar.
 * <p>
 * O livro vai inteiro quando cabe em {@link #BOOK_BUDGET}; senão, vão os parágrafos que mais têm as palavras da
 * pergunta, com os vizinhos. O limite deixa o pedido abaixo de 100 mil tokens, a partir dos quais o Haiku cobra o
 * dobro.
 */
@Service
public class AskService {

    static final int MAX_QUESTION = 500;
    static final int BOOK_BUDGET = 250_000;
    static final int MATERIAL_BUDGET = 12_000;
    static final int MAX_ANSWER = 2_000;
    static final int MAX_SOURCES = 5;
    static final int MAX_QUOTE = 300;
    static final int MIN_TERM = 4;
    /** Palavras da pergunta que não ajudam a achar o trecho. */
    static final Set<String> STOPWORDS = Set.of("qual", "quais", "quando", "onde", "quem", "como", "porque", "sobre", "capitulo", "livro", "isso", "isto", "essa", "esse", "esta", "este", "aquele",
            "aquela", "tinha", "tinham", "ficou", "foram", "estava", "estao", "algum", "alguma", "alguem", "nada",
            "muito", "mais", "menos", "depois", "antes", "ainda", "entao", "pelo", "pela", "para", "sabe", "saber",
            "quantos", "quantas", "acontece", "aconteceu", "historia");

    private final AiUsage usage;
    private final Manuscripts manuscripts;
    private final Universes universes;

    public AskService(AiUsage usage, Manuscripts manuscripts, Universes universes) {
        this.usage = usage;
        this.manuscripts = manuscripts;
        this.universes = universes;
    }

    public record Request(UUID bookId, String question) {
    }

    /** @param blockId o parágrafo do trecho, para o editor abrir no lugar */
    public record Citation(UUID chapterId, int chapter, String chapterTitle, String blockId, String quote) {
    }

    public record Result(String answer, List<Citation> citations, String provider, String model, boolean local,
                         int remaining) {
    }

    public Result ask(UUID ownerId, Request request) {
        String question = request.question() == null ? "" : request.question().strip();
        if (request.bookId() == null) {
            throw ApiException.unprocessable("ask_invalid", "Diga sobre qual livro é a pergunta.");
        }
        if (question.isEmpty() || question.length() > MAX_QUESTION) {
            throw ApiException.unprocessable("ask_invalid",
                    "Escreva a pergunta em até " + MAX_QUESTION + " caracteres.");
        }
        usage.requireAvailable(ownerId);
        BookText book = manuscripts.bookText(ownerId, request.bookId());
        List<ChapterText> chapters = book.chapters().stream()
                .filter(c -> c.blocks().stream().anyMatch(AskService::hasText))
                .toList();
        if (chapters.isEmpty()) {
            throw ApiException.unprocessable("book_empty", "O livro ainda não tem texto para consultar.");
        }
        IdeaModel.Prompt prompt = Prompts.ask(question, material(ownerId, request.bookId(), book), MATERIAL_BUDGET,
                bookText(chapters, question, BOOK_BUDGET));
        IdeaModel model = usage.model();
        Answer answer = usage.run(ownerId, "ask", "book", "ask", () -> model.answer(prompt),
                a -> a.sources() == null ? 0 : a.sources().size());
        String text = answer.answer() == null ? "" : answer.answer().strip();
        if (text.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_bad_answer",
                    "A IA não conseguiu responder agora. Tente de novo ou mude a pergunta.");
        }
        return new Result(text.length() > MAX_ANSWER ? text.substring(0, MAX_ANSWER) + "…" : text,
                cite(answer.sources(), chapters), model.provider(), model.model(), model.local(),
                usage.remaining(ownerId));
    }

    /** Fichas do universo, sinopse e notas: o que não está no texto, mas responde muita pergunta. */
    private List<Section> material(UUID ownerId, UUID bookId, BookText book) {
        var content = manuscripts.bookContent(ownerId, bookId);
        List<Section> material = new ArrayList<>();
        material.add(Section.of("Sinopse do livro", content.synopsis()));
        if (book.universeId() != null) {
            Universes.Brief brief = universes.brief(book.universeId(), null);
            material.add(Section.list("Personagens (fichas)", brief.characters()));
            material.add(Section.list("Relações entre personagens", brief.relations()));
            material.add(Section.list("Linha do tempo", brief.timeline()));
            material.add(Section.list("Lugares (fichas)", brief.places()));
            material.add(Section.list("Universo \"" + brief.name() + "\"", brief.lines()));
            material.add(Section.list("Fichas do mundo", brief.lore()));
        }
        material.add(Section.list("Notas do autor (planos e lembretes)", manuscripts.openNotes(ownerId, bookId)));
        return material;
    }

    /**
     * O texto que vai no pedido, capítulo por capítulo. Cabe tudo? Vai tudo. Senão, os parágrafos com mais
     * palavras da pergunta e os vizinhos de cada um, na ordem do livro, com "[…]" onde há corte.
     */
    static String bookText(List<ChapterText> chapters, String question, int budget) {
        int total = chapters.stream().mapToInt(c -> render(c, null).length()).sum();
        if (total <= budget) {
            return chapters.stream().map(c -> render(c, null)).collect(Collectors.joining("\n\n"));
        }
        Set<String> terms = terms(question);
        List<Block> all = chapters.stream().flatMap(c -> c.blocks().stream()).filter(AskService::hasText).toList();
        List<Integer> ranked = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (score(all.get(i), terms) > 0) {
                ranked.add(i);
            }
        }
        ranked.sort(Comparator.comparingInt((Integer i) -> -score(all.get(i), terms)).thenComparing(i -> i));
        if (ranked.isEmpty()) {
            // Nada da pergunta aparece no texto: vão os capítulos mais recentes, do fim para o começo
            for (int i = all.size() - 1; i >= 0; i--) {
                ranked.add(i);
            }
        }
        Set<String> chosen = new HashSet<>();
        int used = 0;
        for (int index : ranked) {
            for (int near = Math.max(0, index - 1); near <= Math.min(all.size() - 1, index + 1); near++) {
                Block block = all.get(near);
                if (!chosen.contains(block.blockId()) && used + block.text().length() + 2 <= budget) {
                    chosen.add(block.blockId());
                    used += block.text().length() + 2;
                }
            }
            if (used >= budget) {
                break;
            }
        }
        return chapters.stream().map(c -> render(c, chosen)).filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    /** Um capítulo entre marcas; com {@code only}, só aqueles parágrafos (os outros viram "[…]"). */
    private static String render(ChapterText chapter, Set<String> only) {
        StringBuilder out = new StringBuilder();
        boolean gap = false;
        boolean any = false;
        for (Block block : chapter.blocks()) {
            if (!hasText(block)) {
                continue;
            }
            if (only != null && !only.contains(block.blockId())) {
                gap = true;
                continue;
            }
            if (gap) {
                out.append("[…]\n\n");
            }
            gap = false;
            any = true;
            out.append(block.text().strip()).append("\n\n");
        }
        if (!any) {
            return "";
        }
        if (gap) {
            out.append("[…]");
        }
        String title = chapter.title() == null || chapter.title().isBlank()
                ? "" : " titulo=\"" + chapter.title() + "\"";
        return "<capitulo numero=\"" + chapter.position() + "\"" + title + ">\n" + out.toString().strip()
                + "\n</capitulo>";
    }

    /** As palavras da pergunta que servem para achar trechos (sem acento, 4 letras ou mais, sem as comuns). */
    static Set<String> terms(String question) {
        return Arrays.stream(plain(question).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() >= MIN_TERM && !STOPWORDS.contains(t))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static int score(Block block, Set<String> terms) {
        String text = plain(block.text());
        return (int) terms.stream().filter(text::contains).count();
    }

    /**
     * Confere cada trecho citado: precisa estar no livro (no capítulo indicado, ou em outro, se a IA errou o
     * número), exato ou sem diferença de maiúsculas. O que não está cai; repetidos caem.
     */
    static List<Citation> cite(List<Source> sources, List<ChapterText> chapters) {
        List<Citation> citations = new ArrayList<>();
        if (sources == null) {
            return citations;
        }
        Set<String> seen = new HashSet<>();
        for (Source source : sources) {
            if (source == null || source.quote() == null || citations.size() >= MAX_SOURCES) {
                continue;
            }
            String quote = source.quote().strip().replaceAll("^[\"“”'«»…\\s]+|[\"“”'«»…\\s]+$", "");
            if (quote.isEmpty() || quote.length() > MAX_QUOTE) {
                continue;
            }
            Citation found = find(quote, chapters.stream().filter(c -> c.position() == source.chapter()).toList());
            if (found == null) {
                found = find(quote, chapters);
            }
            if (found != null && seen.add(found.blockId() + "|" + found.quote())) {
                citations.add(found);
            }
        }
        return citations;
    }

    private static Citation find(String quote, List<ChapterText> chapters) {
        String lower = quote.toLowerCase(Locale.ROOT);
        for (ChapterText chapter : chapters) {
            for (Block block : chapter.blocks()) {
                if (!hasText(block)) {
                    continue;
                }
                int at = block.text().indexOf(quote);
                if (at < 0) {
                    at = block.text().toLowerCase(Locale.ROOT).indexOf(lower);
                }
                if (at >= 0) {
                    return new Citation(chapter.chapterId(), chapter.position(), chapter.title(), block.blockId(),
                            block.text().substring(at, at + quote.length()));
                }
            }
        }
        return null;
    }

    private static boolean hasText(Block block) {
        return block.text() != null && !block.text().isBlank();
    }

    private static String plain(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
