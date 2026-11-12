package com.booksmith.assist.internal;

import com.booksmith.assist.internal.IdeaModel.Finding;
import com.booksmith.assist.internal.Prompts.Section;
import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.BookText;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;
import com.booksmith.manuscript.api.Manuscripts.TextIssue;
import com.booksmith.universe.api.Universes;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Revisão dos textos ao redor da história (§4.11): a sinopse, o "Sobre o autor" e as fichas dos personagens,
 * comparados com o texto dos capítulos.
 * <ul>
 *   <li><b>Sem IA, livre:</b> ortografia pelo corretor do Booksmith; formatação (espaço duplo, espaço antes da
 *   vírgula, falta de espaço depois dela, frase começando em minúscula); e nomes que divergem da história ("Sergio"
 *   na sinopse, "Sérgio" nos capítulos), avisando qual forma a história mais usa;</li>
 *   <li><b>Com IA (1 uso):</b> fatos que divergem (idade, parentesco, aparência, datas, acontecimentos).</li>
 * </ul>
 * Só aponta; quem corrige é o autor, na tela de cada texto (o app leva até lá).
 */
@Service
public class BookTextsService {

    /** O corretor recusa textos maiores; uma ficha enorme fica sem a ortografia (o resto vale). */
    static final int MAX_PROOFREAD_TEXT = 5_000;
    static final int PROOFREAD_BATCH = 50;
    static final int PROOFREAD_BATCH_CHARS = 35_000;
    /** Vezes que o nome precisa aparecer na história para valer como padrão. */
    static final int MIN_NAME_COUNT = 2;
    static final int MIN_NEAR_COUNT = 3;
    static final int MIN_NEAR_LENGTH = 5;
    static final int MAX_ITEMS = 200;
    static final int BOOK_BUDGET = 200_000;
    static final int MATERIAL_BUDGET = 30_000;
    static final int CONTEXT = 30;

    /** Os campos da ficha, com o nome que aparece na tela. */
    static final Map<String, String> SHEET_LABELS = Map.ofEntries(
            Map.entry("age", "Idade"), Map.entry("birthday", "Aniversário"), Map.entry("occupation", "Ocupação"),
            Map.entry("personality", "Personalidade"), Map.entry("voice", "Jeito de falar"),
            Map.entry("desires", "O que deseja"), Map.entry("fears", "Medos"), Map.entry("secrets", "Segredos"),
            Map.entry("backstory", "História"), Map.entry("notes", "Anotações"));

    private static final Pattern CAPITALIZED = Pattern.compile("(?<![\\p{L}])\\p{Lu}\\p{Ll}{2,}(?![\\p{L}])");
    private static final Pattern DOUBLE_SPACE = Pattern.compile("(?<=\\S) {2,}(?=\\S)");
    private static final Pattern SPACE_BEFORE = Pattern.compile("(?<=\\p{L}) +([,.;:!?])");
    private static final Pattern NO_SPACE_AFTER = Pattern.compile("(?<=\\p{L})([,;])(?=\\p{L})");
    private static final Pattern LOWER_AFTER_STOP = Pattern.compile("(\\p{L}{4,})([.!?]) (\\p{Ll})");

    private final AiUsage usage;
    private final Manuscripts manuscripts;
    private final Universes universes;

    public BookTextsService(AiUsage usage, Manuscripts manuscripts, Universes universes) {
        this.usage = usage;
        this.manuscripts = manuscripts;
        this.universes = universes;
    }

    /**
     * Um texto revisado.
     *
     * @param source {@code synopsis}, {@code about} ou {@code character}
     * @param id     a ficha, quando é de personagem
     * @param label  como aparece na tela ("Sinopse", "Ficha de Lia · Medos")
     */
    public record Text(String key, String source, UUID id, String label, String text) {
    }

    /**
     * Um apontamento.
     *
     * @param quote   o trecho do texto (sinopse, ficha…) com o problema
     * @param context o trecho com algumas palavras em volta, para achar
     * @param kind    {@code spelling}, {@code grammar}, {@code format}, {@code name} ou {@code fact} (IA)
     */
    public record Item(String source, UUID id, String label, String quote, String context, String problem,
                       String suggestion, String kind) {
    }

    public record Result(List<Item> items, int texts, String provider, String model, boolean local, int remaining) {
    }

    /** Sem IA: ortografia, formatação e nomes divergentes. Livre e sem limite. */
    public Result check(UUID ownerId, UUID bookId) {
        BookText book = manuscripts.bookText(ownerId, bookId);
        List<Text> texts = texts(ownerId, bookId, book);
        List<Item> items = new ArrayList<>();
        items.addAll(spelling(ownerId, bookId, texts));
        texts.forEach(t -> items.addAll(format(t)));
        items.addAll(names(texts, book.chapters()));
        List<Item> limited = items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items;
        return new Result(List.copyOf(limited), texts.size(), "regras", "", true,
                usage.enabled() ? usage.remaining(ownerId) : 0);
    }

    /** Com IA (gasta um uso): fatos dos textos que divergem do que a história conta. */
    public Result compare(UUID ownerId, UUID bookId) {
        usage.requireAvailable(ownerId);
        BookText book = manuscripts.bookText(ownerId, bookId);
        List<Text> texts = texts(ownerId, bookId, book);
        List<ChapterText> chapters = book.chapters().stream()
                .filter(c -> c.blocks().stream().anyMatch(b -> b.text() != null && !b.text().isBlank()))
                .toList();
        if (texts.isEmpty() || chapters.isEmpty()) {
            throw ApiException.unprocessable("texts_empty",
                    "Para comparar, o livro precisa de capítulos com texto e de sinopse, \"sobre o autor\" ou fichas.");
        }
        List<Section> material = texts.stream().map(t -> Section.of(t.label(), t.text())).toList();
        IdeaModel.Prompt prompt = Prompts.compareTexts(material, MATERIAL_BUDGET,
                AskService.bookText(chapters, "", BOOK_BUDGET));
        IdeaModel model = usage.model();
        List<Item> items = usage.run(ownerId, "texts", "facts", "compare",
                () -> facts(model.findings(prompt), texts), List::size);
        return new Result(items, texts.size(), model.provider(), model.model(), model.local(),
                usage.remaining(ownerId));
    }

    /** A sinopse, o "Sobre o autor" e cada campo de texto das fichas (com o nome e os apelidos). */
    private List<Text> texts(UUID ownerId, UUID bookId, BookText book) {
        var content = manuscripts.bookContent(ownerId, bookId);
        List<Text> texts = new ArrayList<>();
        add(texts, new Text("synopsis", "synopsis", null, "Sinopse", content.synopsis()));
        add(texts, new Text("about", "about", null, "Sobre o autor", content.pages().aboutAuthor()));
        if (book.universeId() != null) {
            for (Universes.CharacterSheet sheet : universes.characterSheets(book.universeId())) {
                String who = "Ficha de " + sheet.name();
                add(texts, new Text("c:" + sheet.id() + ":name", "character", sheet.id(), who + " · Nome",
                        sheet.name()));
                if (!sheet.aliases().isEmpty()) {
                    add(texts, new Text("c:" + sheet.id() + ":aliases", "character", sheet.id(),
                            who + " · Apelidos", String.join(", ", sheet.aliases())));
                }
                // Só os campos de texto da ficha (o papel, por exemplo, guarda um código como "protagonist")
                sheet.fields().forEach((key, value) -> {
                    if (SHEET_LABELS.containsKey(key)) {
                        add(texts, new Text("c:" + sheet.id() + ":" + key, "character", sheet.id(),
                                who + " · " + SHEET_LABELS.get(key), value));
                    }
                });
            }
        }
        return texts;
    }

    private static void add(List<Text> texts, Text text) {
        if (text.text() != null && !text.text().isBlank()) {
            texts.add(text);
        }
    }

    /** O corretor do Booksmith em lotes (só ortografia e gramática; nome e apelido não passam por ele). */
    private List<Item> spelling(UUID ownerId, UUID bookId, List<Text> texts) {
        List<Item> items = new ArrayList<>();
        Map<String, Text> byKey = new LinkedHashMap<>();
        Map<String, String> batch = new LinkedHashMap<>();
        int size = 0;
        for (Text text : texts) {
            if (text.key().endsWith(":name") || text.key().endsWith(":aliases")
                    || text.text().length() > MAX_PROOFREAD_TEXT) {
                continue;
            }
            if (batch.size() == PROOFREAD_BATCH || size + text.text().length() > PROOFREAD_BATCH_CHARS) {
                items.addAll(proofread(ownerId, bookId, batch, byKey));
                batch.clear();
                size = 0;
            }
            byKey.put(text.key(), text);
            batch.put(text.key(), text.text());
            size += text.text().length();
        }
        if (!batch.isEmpty()) {
            items.addAll(proofread(ownerId, bookId, batch, byKey));
        }
        return items;
    }

    private List<Item> proofread(UUID ownerId, UUID bookId, Map<String, String> batch, Map<String, Text> byKey) {
        List<Item> items = new ArrayList<>();
        manuscripts.proofread(ownerId, bookId, batch).forEach((key, issues) -> {
            Text text = byKey.get(key);
            for (TextIssue issue : issues) {
                if ("style".equals(issue.type()) || issue.offset() + issue.length() > text.text().length()) {
                    continue;
                }
                String quote = text.text().substring(issue.offset(), issue.offset() + issue.length());
                String suggestion = issue.replacements().isEmpty() ? "" : issue.replacements().getFirst();
                items.add(item(text, quote, context(text.text(), issue.offset(), issue.offset() + issue.length()),
                        issue.message(), suggestion, issue.type()));
            }
        });
        return items;
    }

    /** Espaço duplo, espaço antes da pontuação, falta de espaço depois da vírgula, frase em minúscula. */
    static List<Item> format(Text text) {
        List<Item> items = new ArrayList<>();
        String t = text.text();
        Matcher m = DOUBLE_SPACE.matcher(t);
        while (m.find()) {
            items.add(item(text, m.group(), context(t, m.start(), m.end()), "Espaço duplo.", " ", "format"));
        }
        m = SPACE_BEFORE.matcher(t);
        while (m.find()) {
            items.add(item(text, m.group(), context(t, m.start(), m.end()),
                    "Espaço antes de \"" + m.group(1) + "\".", m.group(1), "format"));
        }
        m = NO_SPACE_AFTER.matcher(t);
        while (m.find()) {
            items.add(item(text, m.group(), context(t, m.start(), m.end()),
                    "Falta espaço depois de \"" + m.group(1) + "\".", m.group(1) + " ", "format"));
        }
        m = LOWER_AFTER_STOP.matcher(t);
        while (m.find()) {
            String fixed = m.group(1) + m.group(2) + " " + m.group(3).toUpperCase(Locale.ROOT);
            String quote = m.group(1) + m.group(2) + " " + m.group(3);
            items.add(item(text, quote, context(t, m.start(), m.end()),
                    "A frase começa com letra minúscula.", fixed, "format"));
        }
        return items;
    }

    /**
     * Nomes que divergem da história: a palavra com maiúscula no texto (sinopse, ficha…) que, sem acento e sem
     * maiúsculas, é igual a um nome da história mas está escrita de outro jeito ("Sergio" × "Sérgio"); ou que é
     * nome de ficha, não aparece na história e difere de um nome dela por uma letra ("Kaell" × "Kael").
     */
    static List<Item> names(List<Text> texts, List<ChapterText> chapters) {
        Set<String> known = new HashSet<>();
        texts.stream().filter(t -> t.key().endsWith(":name") || t.key().endsWith(":aliases"))
                .forEach(t -> CAPITALIZED.matcher(t.text()).results().forEach(r -> known.add(fold(r.group()))));
        Map<String, Map<String, Integer>> story = properNames(chapters, known);
        List<Item> items = new ArrayList<>();
        for (Text text : texts) {
            Set<String> seen = new HashSet<>();
            Matcher m = CAPITALIZED.matcher(text.text());
            while (m.find()) {
                String word = m.group();
                if (!seen.add(word)) {
                    continue;
                }
                Map<String, Integer> variants = story.get(fold(word));
                if (variants != null && !variants.containsKey(word)) {
                    Map.Entry<String, Integer> usual = most(variants);
                    if (usual.getValue() >= MIN_NAME_COUNT) {
                        items.add(item(text, word, context(text.text(), m.start(), m.end()),
                                "Na história aparece \"" + usual.getKey() + "\" (" + usual.getValue()
                                        + " vezes); aqui está \"" + word + "\".", usual.getKey(), "name"));
                    }
                } else if (variants == null && "character".equals(text.source()) && word.length() >= MIN_NEAR_LENGTH
                        && (text.key().endsWith(":name") || text.key().endsWith(":aliases"))) {
                    near(story, word).ifPresent(usual -> items.add(item(text, word,
                            context(text.text(), m.start(), m.end()), "\"" + word + "\" não aparece na história, "
                                    + "mas \"" + usual.getKey() + "\" aparece " + usual.getValue() + " vezes.",
                            usual.getKey(), "name")));
                }
            }
        }
        return items;
    }

    /**
     * Palavras com maiúscula no meio da frase, nos capítulos (nomes próprios), agrupadas sem acento e sem
     * maiúsculas: "sergio" → {"Sérgio": 40, "Sergio": 1}. A palavra no começo da frase não conta ("Está"), a não
     * ser que seja nome de ficha ({@code known}): "— Sérgio, vem!" conta.
     */
    static Map<String, Map<String, Integer>> properNames(List<ChapterText> chapters, Set<String> known) {
        Map<String, Map<String, Integer>> names = new HashMap<>();
        for (ChapterText chapter : chapters) {
            for (Block block : chapter.blocks()) {
                String text = block.text() == null ? "" : block.text();
                Matcher m = CAPITALIZED.matcher(text);
                while (m.find()) {
                    if (midSentence(text, m.start()) || known.contains(fold(m.group()))) {
                        names.computeIfAbsent(fold(m.group()), k -> new HashMap<>()).merge(m.group(), 1, Integer::sum);
                    }
                }
            }
        }
        return names;
    }

    /** A palavra vem depois de outra palavra ou de vírgula, não no começo da frase, da fala ou do parágrafo. */
    private static boolean midSentence(String text, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i >= 0 && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == ',');
    }

    private static java.util.Optional<Map.Entry<String, Integer>> near(Map<String, Map<String, Integer>> story,
                                                                       String word) {
        String folded = fold(word);
        return story.entrySet().stream()
                .filter(e -> Math.abs(e.getKey().length() - folded.length()) <= 1)
                .filter(e -> ReviewService.distance(e.getKey(), folded) == 1)
                .map(e -> most(e.getValue()))
                .filter(e -> e.getValue() >= MIN_NEAR_COUNT)
                .max(Map.Entry.comparingByValue());
    }

    private static Map.Entry<String, Integer> most(Map<String, Integer> variants) {
        return variants.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
    }

    /** Fatos divergentes pela IA: cada trecho precisa estar num dos textos (senão cai), que dá a origem. */
    static List<Item> facts(List<Finding> findings, List<Text> texts) {
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Finding finding : findings == null ? List.<Finding>of() : findings) {
            if (finding == null || finding.quote() == null || finding.quote().isBlank()) {
                continue;
            }
            String quote = finding.quote().strip();
            for (Text text : texts) {
                int at = text.text().toLowerCase(Locale.ROOT).indexOf(quote.toLowerCase(Locale.ROOT));
                if (at >= 0 && seen.add(text.key() + "|" + quote.toLowerCase(Locale.ROOT))) {
                    items.add(item(text, text.text().substring(at, at + quote.length()),
                            context(text.text(), at, at + quote.length()), finding.problem(), finding.suggestion(),
                            "fact"));
                    break;
                }
            }
        }
        return items;
    }

    private static Item item(Text text, String quote, String context, String problem, String suggestion,
                             String kind) {
        return new Item(text.source(), text.id(), text.label(), quote, context, problem, suggestion, kind);
    }

    /** O trecho com até {@link #CONTEXT} caracteres de cada lado, sem cortar palavra. */
    static String context(String text, int from, int to) {
        int start = Math.max(0, from - CONTEXT);
        int end = Math.min(text.length(), to + CONTEXT);
        while (start > 0 && Character.isLetter(text.charAt(start - 1)) && start < from) {
            start++;
        }
        while (end < text.length() && Character.isLetter(text.charAt(end)) && end > to) {
            end--;
        }
        return (start > 0 ? "…" : "") + text.substring(start, end).strip() + (end < text.length() ? "…" : "");
    }

    /** Sem acento e em minúsculas: "Sérgio" e "Sergio" viram "sergio". */
    static String fold(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
