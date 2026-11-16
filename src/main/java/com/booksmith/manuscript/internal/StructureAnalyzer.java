package com.booksmith.manuscript.internal;

import com.booksmith.manuscript.internal.ImportedText.Paragraph;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lê a estrutura de um arquivo importado e propõe as divisões do livro para o escritor aprovar: partes, capítulos,
 * capítulos sem número (apresentação, introdução, conclusão…), subcapítulos (seções dentro do capítulo),
 * dedicatória e o sumário do arquivo.
 *
 * <p>Não depende só dos estilos de título: muita gente marca o capítulo com negrito e letra maior, e escreve o
 * sumário (ou o plano do livro) como uma lista numerada sem título. Cada parágrafo curto ganha uma nota a partir de
 * sinais (palavra-chave, estilo de título, tamanho da letra em relação ao corpo do texto, negrito, maiúsculas); um
 * bloco de linhas curtas e numeradas é lido como sumário ({@link OutlineParser}) e cruzado com os títulos do texto.
 * Sem IA: regras determinísticas, rápidas e sem custo.
 */
final class StructureAnalyzer {

    enum Kind {
        /** Parte do livro ("Parte 1"): agrupa capítulos, sem texto próprio. */
        PART,
        /** Capítulo numerado ou com título. */
        CHAPTER,
        /** Capítulo sem número: apresentação, introdução, prefácio, conclusão, epílogo… */
        UNNUMBERED,
        /** Subcapítulo: título dentro do capítulo. */
        SECTION,
        /** Dedicatória: vai para as páginas do livro. */
        DEDICATION,
        /** Parece título, mas fica como texto comum. */
        TEXT,
        /** Sai do livro (linha decorativa, subtítulo solto). */
        REMOVE
    }

    enum Confidence { HIGH, MEDIUM, LOW }

    /**
     * Uma divisão proposta: {@code index} é a posição do parágrafo em {@link Analysis#paragraphs()};
     * {@code words} e {@code excerpt} descrevem o texto até a próxima divisão.
     */
    record Division(int index, Kind kind, String title, Confidence confidence, List<String> reasons, int words,
                    String excerpt, boolean inOutline) {
    }

    /**
     * O sumário encontrado no arquivo: os parágrafos {@code from} a {@code to} (inclusive) e os itens lidos, cada um
     * com o índice da divisão do texto que tem o mesmo título (ou -1, se ainda não tem texto).
     */
    record Outline(int from, int to, boolean titled, List<OutlineParser.Item> items, List<Integer> matches) {

        int withText() {
            return (int) matches.stream().filter(i -> i >= 0).count();
        }
    }

    record Analysis(String documentTitle, List<Paragraph> paragraphs, List<Division> divisions, Outline outline) {
    }

    static final int MAX_TITLE_LENGTH = 120;
    private static final int EXCERPT_LENGTH = 140;
    /** Um bloco de sumário sem título precisa de pelo menos tantas linhas curtas e numeradas. */
    private static final int MIN_OUTLINE_LINES = 6;
    private static final int OUTLINE_LINE_MAX = 180;

    private static final Pattern TOC = Pattern.compile("^(sumario|indice|conteudo|table of contents|contents)$");
    private static final Pattern PART = Pattern.compile(
            "^(parte|part|livro)\\s+([ivxlc]+|\\d+|um|dois|tres|quatro|cinco|seis|sete|oito|nove|dez|"
                    + "primeira|segunda|terceira|quarta|quinta|sexta|setima|oitava|nona|decima)\\b.*");
    private static final Pattern CHAPTER = Pattern.compile(
            "^(capitulo|cap\\.|chapter|licao|aula|modulo|unidade|lesson|dia|semana|devocional|estudo|encontro)"
                    + "\\s*([ivxlc]+|\\d+|um|dois|tres|quatro|cinco|seis|sete|oito|nove|dez)\\b.*");
    private static final Pattern UNNUMBERED = Pattern.compile(
            "^(apresentacao|introducao|prefacio|prologo|nota do autor|nota da autora|palavra do autor|"
                    + "palavra da autora|como usar este livro|antes de comecar|conclusao|consideracoes finais|"
                    + "palavras finais|epilogo|posfacio|oracao final|apendice|anexo|glossario|referencias|"
                    + "bibliografia|agradecimentos|sobre o autor|sobre a autora|prologue|epilogue|introduction|"
                    + "foreword|preface|conclusion)\\b.{0,60}$");
    private static final Pattern DEDICATION = Pattern.compile("^(dedicatoria|dedico este livro|dedicado a)\\b.*");
    /** "1.1 Título", "2.3 - Título": subcapítulo numerado. */
    private static final Pattern NUMBERED_SECTION = Pattern.compile("^\\d{1,4}\\.\\d{1,4}(\\.\\d{1,4}){0,4}\\.?\\s*[-.)–:]?\\s*\\S.*");
    /** "3 - Título": item numerado de um sumário. */
    private static final Pattern NUMBERED_LINE = Pattern.compile("^\\d{1,4}(\\.\\d{1,4}){0,4}\\.?\\s*[-.)–—:]\\s*\\S.*");
    /** Linha só de símbolos ("____", "* * *"): decorativa. */
    private static final Pattern DECORATIVE = Pattern.compile("^[\\s*#~•·⁂§◇◆✦○●=_—–.-]+$");

    private StructureAnalyzer() {
    }

    static boolean isPart(String norm) {
        return PART.matcher(norm).matches();
    }

    static boolean isUnnumbered(String norm) {
        return UNNUMBERED.matcher(norm).matches();
    }

    static boolean isChapterLabel(String norm) {
        return CHAPTER.matcher(norm).matches();
    }

    static Analysis analyze(ImportedText.Result text) {
        List<Paragraph> paragraphs = new ArrayList<>(text.paragraphs().stream().filter(p -> !p.isBlank()).toList());
        String documentTitle = null;
        if (!paragraphs.isEmpty() && paragraphs.getFirst().title()) {
            documentTitle = clip(paragraphs.removeFirst().text());
            while (!paragraphs.isEmpty() && paragraphs.getFirst().subtitle()) {
                paragraphs.removeFirst();
            }
        }
        for (int i = 0; i < paragraphs.size(); i++) {
            if (paragraphs.get(i).backMatter()) {
                paragraphs.subList(i, paragraphs.size()).clear();
                break;
            }
        }

        int body = bodySize(paragraphs);
        int[] outlineRange = findOutline(paragraphs);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (outlineRange.length > 0 && i >= outlineRange[0] && i <= outlineRange[1]) {
                continue; // o sumário não é texto do livro
            }
            Candidate candidate = candidate(i, paragraphs.get(i), body);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        resolveGenericHeadings(candidates);

        Outline outline = null;
        if (outlineRange.length > 0) {
            List<String> lines = new ArrayList<>();
            int first = outlineRange[2] == 1 ? outlineRange[0] + 1 : outlineRange[0];
            for (int i = first; i <= outlineRange[1]; i++) {
                paragraphs.get(i).text().lines().forEach(lines::add); // item com quebra de linha: uma linha por item
            }
            List<OutlineParser.Item> items = OutlineParser.parse(lines);
            outline = new Outline(outlineRange[0], outlineRange[1], outlineRange[2] == 1, items, match(items, candidates));
        }
        dropEmptyLeadingHeadings(candidates, paragraphs.size());

        List<Division> divisions = new ArrayList<>();
        for (int n = 0; n < candidates.size(); n++) {
            Candidate c = candidates.get(n);
            if (c.kind == Kind.TEXT) {
                continue;
            }
            int end = c.kind == Kind.SECTION ? nextAny(candidates, n, paragraphs.size())
                    : nextChapterLevel(candidates, n, paragraphs.size());
            int words = 0;
            StringBuilder excerpt = new StringBuilder();
            for (int i = c.index + 1; i < end; i++) {
                if (inRange(outlineRange, i)) {
                    continue;
                }
                String t = paragraphs.get(i).text().strip();
                words += wordCount(t);
                if (excerpt.length() < EXCERPT_LENGTH && !isDivisionAt(candidates, i)) {
                    excerpt.append(excerpt.isEmpty() ? "" : " ").append(t.replace('\n', ' '));
                }
            }
            divisions.add(new Division(c.index, c.kind, c.title, confidence(c), List.copyOf(c.reasons), words,
                    shorten(excerpt.toString()), c.inOutline));
        }
        return new Analysis(documentTitle, List.copyOf(paragraphs), List.copyOf(divisions), outline);
    }

    // ===== Sumário =====

    /**
     * O sumário: o título "Sumário" seguido de linhas curtas, ou um bloco de pelo menos 6 linhas curtas em que a
     * maioria é numerada ("3 - …", "2.1 - …") ou é parte/capítulo/página sem número. Devolve {de, até, com título}.
     */
    static int[] findOutline(List<Paragraph> paragraphs) {
        for (int i = 0; i < paragraphs.size(); i++) {
            String norm = key(paragraphs.get(i).text());
            if (TOC.matcher(norm).matches()) {
                int end = i;
                Set<String> seen = new HashSet<>();
                for (int j = i + 1; j < paragraphs.size() && outlineLine(paragraphs.get(j)); j++) {
                    String k = OutlineParser.titleKey(paragraphs.get(j).text());
                    if (!seen.add(k)) {
                        break; // o mesmo título de novo: já é o texto do livro
                    }
                    end = j;
                }
                // A última linha seguida de texto corrido é o título do texto que começa ali
                if (end > i + 1 && end + 1 < paragraphs.size() && !outlineLine(paragraphs.get(end + 1))) {
                    end--;
                }
                return end > i ? new int[] {i, end, 1} : new int[0];
            }
        }
        int bestFrom = -1;
        int bestTo = -1;
        int i = 0;
        while (i < paragraphs.size()) {
            if (!outlineLine(paragraphs.get(i))) {
                i++;
                continue;
            }
            int j = i;
            int structured = 0;
            while (j < paragraphs.size() && outlineLine(paragraphs.get(j))) {
                if (structuredLine(paragraphs.get(j))) {
                    structured++;
                }
                j++;
            }
            int end = j - 1;
            // A última linha seguida de texto corrido é o título do texto que começa ali, não um item do sumário
            if (j < paragraphs.size() && end > i && !outlineLine(paragraphs.get(j))) {
                end--;
            }
            int lines = end - i + 1;
            if (lines >= MIN_OUTLINE_LINES && structured * 10 >= lines * 6 && lines > bestTo - bestFrom + 1) {
                bestFrom = i;
                bestTo = end;
            }
            i = j;
        }
        return bestFrom < 0 ? new int[0] : new int[] {bestFrom, bestTo, 0};
    }

    /** Linha (ou parágrafo com quebras de linha) de sumário: cada linha curta, nada de texto corrido. */
    private static boolean outlineLine(Paragraph paragraph) {
        String t = paragraph.text().strip();
        if (t.isEmpty() || DECORATIVE.matcher(t).matches() || t.length() > OUTLINE_LINE_MAX * 4) {
            return false;
        }
        return t.lines().allMatch(line -> line.strip().length() <= OUTLINE_LINE_MAX
                && !(line.strip().length() > 100 && line.strip().endsWith(".")));
    }

    private static boolean structuredLine(Paragraph paragraph) {
        String t = paragraph.text().strip().lines().findFirst().orElse("").strip();
        String norm = key(t);
        return NUMBERED_LINE.matcher(t).matches() || PART.matcher(norm).matches() || CHAPTER.matcher(norm).matches()
                || UNNUMBERED.matcher(norm).matches();
    }

    /**
     * Para cada item do sumário, a divisão do texto com o mesmo título (cada divisão casa uma vez só): título igual;
     * senão, quase igual (70% das palavras); senão, o mesmo número ("2 - …" e "Capítulo 2 - …") com metade das
     * palavras em comum. Rascunho de sumário e texto final quase nunca batem letra por letra.
     */
    private static List<Integer> match(List<OutlineParser.Item> items, List<Candidate> candidates) {
        List<Candidate> pool = candidates.stream().filter(c -> c.kind != Kind.REMOVE).toList();
        Set<Candidate> used = new HashSet<>();
        List<Integer> matches = new ArrayList<>();
        for (OutlineParser.Item item : items) {
            String number = OutlineParser.number(item.title());
            Candidate found = null;
            double best = 0;
            for (Candidate c : pool) {
                if (used.contains(c)) {
                    continue;
                }
                String key = OutlineParser.titleKey(c.title);
                double score = key.equals(item.key()) ? 1.0 : OutlineParser.similarity(key, item.key());
                boolean sameNumber = number != null && number.equals(OutlineParser.number(c.title));
                boolean accept = score >= 0.7 || (sameNumber && score >= 0.5);
                if (accept && score > best) {
                    best = score;
                    found = c;
                }
            }
            if (found == null) {
                matches.add(-1);
                continue;
            }
            used.add(found);
            found.inOutline = true;
            found.reasons.add("Está no sumário do arquivo");
            if (found.kind == Kind.TEXT) {
                found.kind = item.kind() == OutlineParser.Kind.SECTION ? Kind.SECTION : Kind.CHAPTER;
            }
            matches.add(found.index);
        }
        return matches;
    }

    // ===== Sinais =====

    private static final class Candidate {
        final int index;
        final String title;
        Kind kind;
        /** Peso visual: estilo de título, tamanho, negrito, maiúsculas. */
        final int weight;
        final boolean keyword;
        final boolean headingStyle;
        boolean inOutline;
        final List<String> reasons = new ArrayList<>();

        Candidate(int index, String title, Kind kind, int weight, boolean keyword, boolean headingStyle) {
            this.index = index;
            this.title = title;
            this.kind = kind;
            this.weight = weight;
            this.keyword = keyword;
            this.headingStyle = headingStyle;
        }
    }

    private static Candidate candidate(int index, Paragraph paragraph, int body) {
        String raw = paragraph.text().strip();
        if (raw.isEmpty() || raw.contains("\n") || raw.length() > MAX_TITLE_LENGTH) {
            return null;
        }
        String norm = key(raw);
        boolean decorative = DECORATIVE.matcher(raw).matches();
        int level = paragraph.title() ? 1 : paragraph.headingLevel();
        boolean bold = paragraph.allBold();
        boolean caps = raw.length() >= 4 && raw.equals(raw.toUpperCase(Locale.ROOT)) && raw.chars().anyMatch(Character::isLetter);
        double ratio = body > 0 && paragraph.size() > 0 ? (double) paragraph.size() / body : 1.0;
        boolean bigger = ratio >= 1.15;
        boolean sentence = raw.length() > 60 && raw.endsWith(".") && level == 0;

        Kind kind = null;
        boolean keyword = true;
        if (PART.matcher(norm).matches()) {
            kind = Kind.PART;
        } else if (CHAPTER.matcher(norm).matches()) {
            kind = Kind.CHAPTER;
        } else if (UNNUMBERED.matcher(norm).matches()) {
            kind = Kind.UNNUMBERED;
        } else if (DEDICATION.matcher(norm).matches()) {
            kind = Kind.DEDICATION;
        } else if (NUMBERED_SECTION.matcher(norm).matches()) {
            kind = Kind.SECTION;
        } else {
            keyword = false;
        }
        boolean looksLikeHeading = level > 0 || bigger || (bold && raw.length() <= 80) || (caps && raw.length() <= 70);
        if (decorative) {
            return looksLikeHeading ? removable(index, raw) : null;
        }
        if (!keyword && (!looksLikeHeading || sentence)) {
            return null;
        }
        if (keyword && !looksLikeHeading && raw.length() > 70) {
            return null; // frase comum que começa com "Dia 3 de…" ou "Introdução ao…" no meio do texto
        }
        int weight = (level > 0 ? (4 - Math.min(level, 3)) * 10 : 0) + (int) Math.round((ratio - 1) * 40)
                + (bold ? 4 : 0) + (caps ? 2 : 0);
        Candidate c = new Candidate(index, clip(raw), kind == null ? Kind.TEXT : kind, weight, keyword, level > 0);
        if (keyword) {
            c.reasons.add(switch (c.kind) {
                case PART -> "Começa com \"Parte\"";
                case CHAPTER -> "Começa com \"" + firstWord(raw) + "\" e um número";
                case UNNUMBERED -> "\"" + firstWord(raw) + "\" costuma ser uma página sem número";
                case DEDICATION -> "É a dedicatória";
                case SECTION -> "Numerado como subcapítulo (1.1)";
                default -> "";
            });
        }
        if (level > 0) {
            c.reasons.add(paragraph.title() ? "Estilo Título do Word" : "Estilo Título " + level + " do Word");
        }
        if (bigger) {
            c.reasons.add("Letra maior que o texto (%d pt)".formatted(Math.round(paragraph.size() / 2.0)));
        }
        if (bold && level == 0) {
            c.reasons.add("Todo em negrito");
        }
        if (caps) {
            c.reasons.add("Em maiúsculas");
        }
        return c;
    }

    private static Candidate removable(int index, String raw) {
        Candidate c = new Candidate(index, clip(raw), Kind.REMOVE, 0, false, false);
        c.reasons.add("Linha decorativa");
        return c;
    }

    /**
     * Títulos sem palavra-chave ganham o tipo pela aparência, comparada com a dos capítulos reconhecidos: parecido ou
     * mais forte → capítulo; mais fraco → subcapítulo. Sem capítulo reconhecido, o visual mais comum é o capítulo.
     */
    private static void resolveGenericHeadings(List<Candidate> candidates) {
        List<Integer> chapterWeights = candidates.stream()
                .filter(c -> c.keyword && (c.kind == Kind.CHAPTER || c.kind == Kind.UNNUMBERED))
                .map(c -> c.weight).sorted().toList();
        Integer reference = chapterWeights.isEmpty() ? null : chapterWeights.get(chapterWeights.size() / 2);
        if (reference == null) {
            Map<Integer, Integer> counts = new HashMap<>();
            candidates.stream().filter(c -> c.kind == Kind.TEXT).forEach(c -> counts.merge(c.weight, 1, Integer::sum));
            reference = counts.entrySet().stream().filter(e -> e.getValue() >= 2)
                    .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).orElse(null);
        }
        for (Candidate c : candidates) {
            if (c.kind != Kind.TEXT) {
                continue;
            }
            if (reference == null) {
                c.kind = c.headingStyle ? Kind.CHAPTER : Kind.TEXT;
            } else if (c.weight >= reference - 3) {
                c.kind = Kind.CHAPTER;
                c.reasons.add("Mesma aparência dos capítulos");
            } else if (c.weight > 0) {
                c.kind = Kind.SECTION;
                c.reasons.add("Título menor que o dos capítulos");
            }
        }
    }

    /**
     * Título sem texto antes de outro capítulo, no começo do arquivo (o subtítulo do livro, uma linha de efeito):
     * sugere tirar. Partes não têm texto mesmo, e ficam.
     */
    private static void dropEmptyLeadingHeadings(List<Candidate> candidates, int size) {
        boolean seenChapter = false;
        for (int n = 0; n < candidates.size(); n++) {
            Candidate c = candidates.get(n);
            boolean chapterLevel = c.kind == Kind.CHAPTER || c.kind == Kind.UNNUMBERED;
            // Antes do primeiro capítulo, um "subcapítulo" não tem a que pertencer: conta como título solto
            boolean loose = chapterLevel || (c.kind == Kind.SECTION && !seenChapter);
            if (loose && !c.keyword && !c.inOutline && !seenChapter) {
                int end = nextChapterLevel(candidates, n, size);
                boolean empty = true;
                for (int i = c.index + 1; i < end && empty; i++) {
                    empty = isDivisionAt(candidates, i);
                }
                if (end == c.index + 1 || empty) {
                    c.kind = Kind.REMOVE;
                    c.reasons.add("Sem texto antes da próxima divisão (subtítulo ou enfeite)");
                    continue;
                }
            }
            seenChapter |= chapterLevel;
        }
    }

    /** O fim do capítulo: a próxima parte, capítulo, página sem número ou dedicatória. */
    private static int nextChapterLevel(List<Candidate> candidates, int n, int size) {
        for (int m = n + 1; m < candidates.size(); m++) {
            Kind kind = candidates.get(m).kind;
            if (kind == Kind.PART || kind == Kind.CHAPTER || kind == Kind.UNNUMBERED || kind == Kind.DEDICATION) {
                return candidates.get(m).index;
            }
        }
        return size;
    }

    /** O fim do subcapítulo: qualquer divisão seguinte. */
    private static int nextAny(List<Candidate> candidates, int n, int size) {
        for (int m = n + 1; m < candidates.size(); m++) {
            if (candidates.get(m).kind != Kind.TEXT) {
                return candidates.get(m).index;
            }
        }
        return size;
    }

    private static boolean isDivisionAt(List<Candidate> candidates, int index) {
        for (Candidate c : candidates) {
            if (c.index == index && c.kind != Kind.TEXT) {
                return true;
            }
        }
        return false;
    }

    private static boolean inRange(int[] range, int i) {
        return range.length > 0 && i >= range[0] && i <= range[1];
    }

    private static Confidence confidence(Candidate c) {
        if (c.inOutline || (c.keyword && (c.headingStyle || c.weight >= 4))) {
            return Confidence.HIGH;
        }
        if (c.keyword || c.headingStyle) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    /** O tamanho mais comum entre os parágrafos longos (o corpo do texto); 0 se o arquivo não diz. */
    static int bodySize(List<Paragraph> paragraphs) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Paragraph p : paragraphs) {
            if (p.size() > 0 && p.text().length() > 120) {
                counts.merge(p.size(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
    }

    /** Sem acento, minúsculo, espaços simples: "Introdução " e "INTRODUCAO" são o mesmo título. */
    static String key(String text) {
        String plain = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[\\s ]+", " ").replaceAll("[:.\\s]+$", "").strip();
    }

    private static String firstWord(String text) {
        String word = text.strip().split("[\\s:.\\-–—]+", 2)[0];
        return word.isEmpty() ? text : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    static int wordCount(String text) {
        String t = text.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static String clip(String text) {
        String t = text.replace('\n', ' ').strip();
        return t.length() > 200 ? t.substring(0, 200).strip() : t;
    }

    private static String shorten(String text) {
        if (text.length() <= EXCERPT_LENGTH) {
            return text;
        }
        int cut = text.lastIndexOf(' ', EXCERPT_LENGTH);
        return text.substring(0, cut > 40 ? cut : EXCERPT_LENGTH).strip() + "…";
    }
}
