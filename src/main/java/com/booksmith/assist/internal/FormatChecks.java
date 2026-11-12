package com.booksmith.assist.internal;

import com.booksmith.assist.internal.ReviewService.Item;
import com.booksmith.manuscript.api.Manuscripts.Block;
import com.booksmith.manuscript.api.Manuscripts.BookText;
import com.booksmith.manuscript.api.Manuscripts.ChapterText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Revisão de formatação sem IA: regras que olham o capítulo e o resto do livro. Instantânea, de graça e sem
 * alarme falso de modelo; cobre o que os modelos baratos deixam passar. Cada ponto vira um apontamento próprio,
 * com o tipo da regra ({@code kind}), para o app marcar e corrigir todos de uma vez.
 * <ul>
 *   <li>{@code dialogue}: falas com aspas num livro que usa travessão (ou as duas coisas no mesmo capítulo, com
 *   o travessão na maioria). Cada parágrafo vem convertido para travessão, pronto para trocar. O resto do livro
 *   só vira padrão se usar o seu jeito mais vezes do que este capítulo usa o outro: com dois capítulos
 *   empatados, não dá para saber qual destoa. Livro com aspas e capítulo com travessão: só o aviso;</li>
 *   <li>{@code capital}: fala que começa com letra minúscula (fora dos parágrafos já convertidos, que saem com a
 *   maiúscula);</li>
 *   <li>{@code honorific}: tratamento escrito de dois jeitos no livro ("dona Lurdes" e "Dona Lurdes"): vale o
 *   mais usado;</li>
 *   <li>{@code tag}: a mesma marcação de fala ("perguntou Ana") em parágrafos próximos (só o aviso).</li>
 * </ul>
 */
final class FormatChecks {

    /** O capítulo inteiro: um romance tem centenas de falas, e todas precisam aparecer para corrigir em lote. */
    static final int MAX_ITEMS = 300;
    /** Distância, em parágrafos, para a mesma marcação de fala contar como repetida. */
    static final int TAG_WINDOW = 3;
    /** Falas no resto do livro para valer como padrão. */
    static final int MIN_PATTERN = 3;

    private static final String DASHES = "—–";
    private static final String OPEN_QUOTES = "\"“«";
    private static final Pattern HONORIFIC = Pattern.compile(
            "(?<![\\p{L}])(dona|seu|tia|tio|doutor|doutora|padre|dom|irmã|irmão)\\s+(\\p{Lu}\\p{L}+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String SPEECH_VERBS = "disse|perguntou|respondeu|falou|gritou|sussurrou|murmurou|"
            + "comentou|exclamou|retrucou|repetiu|continuou|insistiu|completou|explicou";
    private static final Pattern TAG_AFTER = Pattern.compile(
            "(?<![\\p{L}])(" + SPEECH_VERBS + ")\\s+(\\p{Lu}\\p{L}+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TAG_BEFORE = Pattern.compile(
            "(?<![\\p{L}])(\\p{Lu}\\p{L}+)\\s+(" + SPEECH_VERBS + ")(?![\\p{L}])",
            Pattern.UNICODE_CASE);
    /**
     * Palavras que, logo depois da fala, começam a marcação ("— Sim — disse ela"; "— Sim? — eu perguntei"):
     * no travessão, vão em minúscula. Um nome ("— Sim — Ana disse") fica como está.
     */
    private static final Set<String> TAG_STARTERS = Set.of("disse", "perguntou", "respondeu", "falou", "gritou",
            "sussurrou", "murmurou", "comentou", "exclamou", "retrucou", "repetiu", "continuou", "insistiu",
            "completou", "explicou", "pensou", "perguntei", "respondi", "falei", "gritei", "sussurrei", "murmurei",
            "comentei", "expliquei", "pensei", "repeti", "insisti", "eu", "ele", "ela", "nós", "eles", "elas",
            "você", "vocês");

    private FormatChecks() {
    }

    enum Style { DASH, QUOTE, NONE }

    static List<Item> check(BookText book, ChapterText chapter) {
        List<Item> items = new ArrayList<>();
        Set<String> converted = dialogueStyle(book, chapter, items);
        speechCapitals(chapter, converted, items);
        honorifics(book, chapter, items);
        repeatedTags(chapter, items);
        return items.size() > MAX_ITEMS ? List.copyOf(items.subList(0, MAX_ITEMS)) : items;
    }

    /** Como a fala começa no parágrafo: travessão, aspas ou não é fala. */
    static Style style(String text) {
        String t = text == null ? "" : text.stripLeading();
        if (t.isEmpty()) {
            return Style.NONE;
        }
        char first = t.charAt(0);
        if (DASHES.indexOf(first) >= 0 || t.startsWith("- ")) {
            return Style.DASH;
        }
        return OPEN_QUOTES.indexOf(first) >= 0 ? Style.QUOTE : Style.NONE;
    }

    /** @return os parágrafos convertidos para travessão (já saem com a fala em maiúscula) */
    private static Set<String> dialogueStyle(BookText book, ChapterText chapter, List<Item> items) {
        int dashBook = 0;
        int quoteBook = 0;
        for (ChapterText other : book.chapters()) {
            if (other.chapterId().equals(chapter.chapterId())) {
                continue;
            }
            for (Block block : other.blocks()) {
                Style s = style(block.text());
                dashBook += s == Style.DASH ? 1 : 0;
                quoteBook += s == Style.QUOTE ? 1 : 0;
            }
        }
        Block firstDash = null;
        int dashHere = 0;
        int quoteHere = 0;
        for (Block block : chapter.blocks()) {
            Style s = style(block.text());
            if (s == Style.DASH) {
                dashHere++;
                firstDash = firstDash == null ? block : firstDash;
            } else if (s == Style.QUOTE) {
                quoteHere++;
            }
        }
        Set<String> converted = new HashSet<>();
        if (dashBook >= MIN_PATTERN && dashBook > quoteBook && dashBook > quoteHere && quoteHere > 0) {
            convertToDashes(chapter, "Fala com aspas; no resto do livro as falas usam travessão (—).", converted,
                    items);
        } else if (quoteBook >= MIN_PATTERN && quoteBook > dashBook && quoteBook > dashHere && dashHere > 0) {
            items.add(new Item(firstDash.blockId(), opening(firstDash.text()),
                    "As falas deste capítulo usam travessão (" + dashHere + "), e no resto do livro usam aspas.",
                    "Use aspas também aqui, para manter o padrão do livro.", false, "dialogue"));
        } else if (dashHere > 0 && quoteHere > 0) {
            if (dashHere >= quoteHere) {
                convertToDashes(chapter, "Fala com aspas; neste capítulo a maioria das falas usa travessão (—).",
                        converted, items);
            } else {
                items.add(new Item(firstDash.blockId(), opening(firstDash.text()),
                        "Este capítulo mistura falas com travessão (" + dashHere + ") e com aspas (" + quoteHere
                                + ").", "Escolha um jeito e use nas falas do livro todo; aqui o mais usado são as "
                        + "aspas.", false, "dialogue"));
            }
        }
        return converted;
    }

    private static void convertToDashes(ChapterText chapter, String problem, Set<String> converted,
                                        List<Item> items) {
        for (Block block : chapter.blocks()) {
            if (style(block.text()) != Style.QUOTE) {
                continue;
            }
            String dashed = toDashes(block.text());
            if (!dashed.equals(block.text())) {
                converted.add(block.blockId());
                items.add(new Item(block.blockId(), block.text(), problem, dashed, true, "dialogue"));
            }
        }
    }

    /**
     * Converte um parágrafo de fala com aspas para o travessão, como se escreve no Brasil:
     * {@code "Sim" respondeu Ana.} → {@code — Sim — respondeu Ana.};
     * {@code "Vem?" Eu perguntei.} → {@code — Vem? — eu perguntei.};
     * {@code "A" disse ela. "B"} → {@code — A — disse ela. — B}. A fala começa com maiúscula; o ponto final da
     * fala cai quando vem a marcação ("— Quebrou — respondi."). Aspas sem fechar valem até o fim do parágrafo.
     */
    static String toDashes(String paragraph) {
        List<String[]> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSpeech = false;
        for (int i = 0; i < paragraph.length(); i++) {
            char c = paragraph.charAt(i);
            boolean opens = !inSpeech && (c == '"' || c == '“' || c == '«');
            boolean closes = inSpeech && (c == '"' || c == '”' || c == '»');
            if (opens || closes) {
                parts.add(new String[] {inSpeech ? "S" : "N", current.toString()});
                current.setLength(0);
                inSpeech = !inSpeech;
            } else {
                current.append(c);
            }
        }
        parts.add(new String[] {inSpeech ? "S" : "N", current.toString()});

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String kind = parts.get(i)[0];
            String text = parts.get(i)[1].strip();
            if (text.isEmpty()) {
                continue;
            }
            if ("S".equals(kind)) {
                String next = nextNarration(parts, i);
                if (next != null && startsTag(next)) {
                    text = text.replaceAll("[.,]$", "");
                } else {
                    text = text.replaceAll(",$", "");
                }
                out.append(out.isEmpty() ? "— " : " — ").append(capitalize(text));
            } else {
                text = text.replaceAll("^[,.]\\s*", "");
                if (text.isEmpty()) {
                    continue;
                }
                if (out.isEmpty()) {
                    out.append(text);
                } else {
                    out.append(" — ").append(startsTag(text) ? decapitalize(text) : text);
                }
            }
        }
        return out.toString();
    }

    private static String nextNarration(List<String[]> parts, int index) {
        for (int j = index + 1; j < parts.size(); j++) {
            String text = parts.get(j)[1].strip().replaceAll("^[,.]\\s*", "");
            if (!text.isEmpty()) {
                return "N".equals(parts.get(j)[0]) ? text : null;
            }
        }
        return null;
    }

    private static boolean startsTag(String narration) {
        String first = narration.split("[^\\p{L}]+", 2)[0].toLowerCase(Locale.ROOT);
        return TAG_STARTERS.contains(first);
    }

    private static String capitalize(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return text.substring(0, i) + Character.toUpperCase(text.charAt(i)) + text.substring(i + 1);
            }
        }
        return text;
    }

    private static String decapitalize(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /** Fala que começa com minúscula: no começo do parágrafo, ou aspas abertas depois de um ponto. */
    private static void speechCapitals(ChapterText chapter, Set<String> converted, List<Item> items) {
        for (Block block : chapter.blocks()) {
            String text = block.text();
            if (text == null || converted.contains(block.blockId())) {
                continue;
            }
            for (int start : speechStarts(text)) {
                int letter = start;
                while (letter < text.length() && !Character.isLetter(text.charAt(letter))) {
                    letter++;
                }
                if (letter >= text.length() || !Character.isLowerCase(text.charAt(letter))) {
                    continue;
                }
                int end = letter;
                while (end < text.length() && Character.isLetter(text.charAt(end))) {
                    end++;
                }
                String quote = text.substring(start, end);
                String fixed = text.substring(start, letter) + Character.toUpperCase(text.charAt(letter))
                        + text.substring(letter + 1, end);
                items.add(new Item(block.blockId(), quote, "A fala começa com letra minúscula.", fixed, true,
                        "capital"));
            }
        }
    }

    /** Onde começa cada fala no parágrafo: o travessão inicial, ou cada aspas de abertura. */
    static List<Integer> speechStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        String t = text.stripLeading();
        int offset = text.length() - t.length();
        if (style(t) == Style.DASH) {
            starts.add(offset);
            return starts;
        }
        boolean open = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean opening = c == '“' || c == '«' || (c == '"' && !open);
            if (c == '"') {
                open = !open;
            }
            if (!opening) {
                continue;
            }
            String before = text.substring(0, i).strip();
            if (before.isEmpty() || ".!?:…".indexOf(before.charAt(before.length() - 1)) >= 0) {
                starts.add(i);
            }
        }
        return starts;
    }

    /** "dona Lurdes" e "Dona Lurdes" no mesmo livro: aponta no capítulo a forma menos usada. */
    private static void honorifics(BookText book, ChapterText chapter, List<Item> items) {
        Map<String, Map<String, Integer>> forms = new HashMap<>();
        for (ChapterText c : book.chapters()) {
            for (Block block : c.blocks()) {
                Matcher m = HONORIFIC.matcher(block.text() == null ? "" : block.text());
                while (m.find()) {
                    forms.computeIfAbsent(m.group().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "),
                            k -> new HashMap<>()).merge(m.group().replaceAll("\\s+", " "), 1, Integer::sum);
                }
            }
        }
        for (Block block : chapter.blocks()) {
            Matcher m = HONORIFIC.matcher(block.text() == null ? "" : block.text());
            Set<String> seen = new HashSet<>();
            while (m.find()) {
                String found = m.group();
                Map<String, Integer> variants = forms.get(found.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
                if (variants == null || variants.size() < 2 || !seen.add(found)) {
                    continue;
                }
                String usual = variants.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                String normalized = found.replaceAll("\\s+", " ");
                if (usual.equals(normalized) || variants.get(normalized) >= variants.get(usual)) {
                    continue;
                }
                items.add(new Item(block.blockId(), found,
                        "No livro aparece \"" + usual + "\" " + variants.get(usual) + " vez(es) e \"" + normalized
                                + "\" " + variants.get(normalized) + ".", usual, true, "honorific"));
            }
        }
    }

    /** A mesma marcação ("perguntou Ana", "Ana perguntou") a até {@link #TAG_WINDOW} parágrafos da anterior. */
    private static void repeatedTags(ChapterText chapter, List<Item> items) {
        Map<String, Integer> lastSeen = new HashMap<>();
        int index = 0;
        for (Block block : chapter.blocks()) {
            String text = block.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            index++;
            for (String[] tag : tags(text)) {
                Integer previous = lastSeen.put(tag[0], index);
                if (previous != null && index - previous <= TAG_WINDOW) {
                    items.add(new Item(block.blockId(), tag[1],
                            "A mesma marcação de fala (\"" + tag[1] + "\") aparece de novo logo em seguida.",
                            "Corte uma das marcações (o leitor já sabe quem fala) ou troque por uma ação do "
                                    + "personagem.", false, "tag"));
                }
            }
        }
    }

    /** Marcações de fala do parágrafo: {chave normalizada, texto como está}. */
    static List<String[]> tags(String text) {
        List<String[]> tags = new ArrayList<>();
        Matcher after = TAG_AFTER.matcher(text);
        while (after.find()) {
            tags.add(new String[] {(after.group(1) + " " + after.group(2)).toLowerCase(Locale.ROOT), after.group()});
        }
        Matcher before = TAG_BEFORE.matcher(text);
        while (before.find()) {
            tags.add(new String[] {(before.group(2) + " " + before.group(1)).toLowerCase(Locale.ROOT),
                    before.group()});
        }
        return tags;
    }

    /** O começo do parágrafo, para "Ir ao trecho" achar o lugar (até 40 caracteres, sem cortar palavra). */
    private static String opening(String text) {
        String t = text.strip();
        if (t.length() <= 40) {
            return t;
        }
        int cut = t.lastIndexOf(' ', 40);
        return t.substring(0, cut > 10 ? cut : 40);
    }
}
