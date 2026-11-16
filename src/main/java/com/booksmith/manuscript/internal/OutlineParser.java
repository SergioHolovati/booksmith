package com.booksmith.manuscript.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê um sumário em texto, linha a linha, e devolve a estrutura do livro: partes, capítulos (numerados ou sem
 * número) e subcapítulos. Serve para o sumário colado pelo escritor ("Criar pelo sumário") e para o sumário (ou o
 * plano de capítulos) encontrado dentro de um arquivo importado.
 *
 * <p>Regras, na ordem: "Parte 1" → parte; "Capítulo 3 - …", "Lição 2", "Dia 5" → capítulo; "Introdução",
 * "Apresentação", "Conclusão"… → capítulo sem número; "2.1 - …" ou linha recuada/com marcador logo depois de um
 * capítulo → subcapítulo; "3 - …" → capítulo; o resto → capítulo. Pontinhos e número de página no fim saem.
 */
final class OutlineParser {

    enum Kind { PART, CHAPTER, UNNUMBERED, SECTION }

    /** {@code title}: como o escritor escreveu, sem o número de página; {@code key}: para comparar com o texto. */
    record Item(Kind kind, String title, String key) {
    }

    static final int MAX_ITEMS = 400;
    static final int MAX_TITLE = 200;

    /** Número de página com pontinhos ("Introdução ....... 7") ou depois de tabulação. */
    private static final Pattern PAGE_LEADER = Pattern.compile("(?:\\s*[.·…_]{2,}\\s*|\\t+)\\d{1,4}\\s*$");
    /** Número de página só com espaço ("Introdução 7"): só sai se o título tiver pelo menos duas palavras antes. */
    private static final Pattern PAGE_SPACE = Pattern.compile("^(\\S+(?:\\s+\\S+)+?)\\s+\\d{1,4}\\s*$");
    private static final Pattern DECORATIVE = Pattern.compile("^[\\s*#~•·⁂§◇◆✦○●=_—–.-]+$");
    private static final Pattern BULLET = Pattern.compile("^[-–—•·*+]\\s+");
    private static final Pattern NUMBERED_SECTION = Pattern.compile("^\\d{1,4}\\.\\d{1,4}(?:\\.\\d{1,4}){0,4}\\.?\\s*[-–—.):]?\\s*(.+)$");
    private static final Pattern NUMBERED_CHAPTER = Pattern.compile("^(\\d{1,3})\\s*[-–—.):]\\s*(.+)$");
    /** "Capítulo 3 - Título", "Lição 2: Título", "Dia 5" → o título sem o rótulo, para comparar. */
    private static final Pattern LABEL = Pattern.compile(
            "^(?:cap[ií]tulo|cap\\.|chapter|li[cç][aã]o|aula|m[oó]dulo|unidade|lesson|dia|semana|devocional|estudo|"
                    + "encontro)\\s*(?:[ivxlc]+|\\d+)\\s*[-–—.):]?\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^\\d{1,4}(?:\\.\\d{1,4}){0,4}\\.?\\s*[-–—.):]?\\s*");

    private OutlineParser() {
    }

    static List<Item> parse(List<String> lines) {
        List<Item> items = new ArrayList<>();
        boolean afterChapter = false;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            boolean indented = line.startsWith(" ") || line.startsWith("\t");
            if (DECORATIVE.matcher(line).matches()) {
                continue;
            }
            String text = stripPage(line.strip());
            boolean bullet = BULLET.matcher(text).find();
            text = BULLET.matcher(text).replaceAll("").strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > MAX_TITLE) {
                text = text.substring(0, MAX_TITLE).strip();
            }
            String norm = StructureAnalyzer.key(text);
            Kind kind;
            if (StructureAnalyzer.isPart(norm)) {
                kind = Kind.PART;
            } else if (StructureAnalyzer.isUnnumbered(norm)) {
                kind = Kind.UNNUMBERED;
            } else if (StructureAnalyzer.isChapterLabel(norm)) {
                kind = Kind.CHAPTER;
            } else if (NUMBERED_SECTION.matcher(text).matches()) {
                kind = Kind.SECTION;
            } else if (NUMBERED_CHAPTER.matcher(text).matches()) {
                kind = Kind.CHAPTER;
            } else if ((indented || bullet) && afterChapter) {
                kind = Kind.SECTION;
            } else {
                kind = Kind.CHAPTER;
            }
            if (kind == Kind.SECTION && !afterChapter) {
                kind = Kind.CHAPTER; // subcapítulo sem capítulo antes: vira capítulo
            }
            afterChapter = kind != Kind.PART;
            items.add(new Item(kind, text, titleKey(text)));
            if (items.size() >= MAX_ITEMS) {
                break;
            }
        }
        return items;
    }

    /**
     * Tira o número de página do fim: com pontinhos ou tabulação, sempre; só com espaço, quando sobra um título de
     * duas palavras ou mais que não seja "Parte 1", "Capítulo 3", "Dia 5" (aí o número é do próprio título).
     */
    static String stripPage(String line) {
        String text = PAGE_LEADER.matcher(line).replaceAll("").strip();
        if (!text.equals(line.strip())) {
            return text;
        }
        Matcher spaced = PAGE_SPACE.matcher(text);
        if (spaced.matches()) {
            String norm = StructureAnalyzer.key(text);
            if (!StructureAnalyzer.isPart(norm) && !StructureAnalyzer.isChapterLabel(norm)) {
                return spaced.group(1).strip();
            }
        }
        return text;
    }

    /** O número do item ("3" em "Capítulo 3 - …" ou "3 - …"; "2.1" em "2.1 - …"), ou null. */
    static String number(String title) {
        String text = title == null ? "" : title.strip();
        Matcher label = Pattern.compile("^(?:cap[ií]tulo|cap\\.|chapter|li[cç][aã]o|aula|m[oó]dulo|unidade|lesson|dia|"
                + "semana)\\s*(\\d+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        if (label.find()) {
            return label.group(1);
        }
        Matcher numbered = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s*[-–—.):]").matcher(text);
        return numbered.find() ? numbered.group(1) : null;
    }

    /** Semelhança entre dois títulos já em {@link #titleKey}: palavras em comum / palavras no total. */
    static double similarity(String a, String b) {
        java.util.Set<String> left = new java.util.HashSet<>(List.of(a.split(" ")));
        java.util.Set<String> right = new java.util.HashSet<>(List.of(b.split(" ")));
        left.remove("");
        right.remove("");
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        java.util.Set<String> both = new java.util.HashSet<>(left);
        both.retainAll(right);
        java.util.Set<String> all = new java.util.HashSet<>(left);
        all.addAll(right);
        return (double) both.size() / all.size();
    }

    static List<Item> parse(String text) {
        return parse(text == null ? List.of() : text.lines().toList());
    }

    /**
     * Chave para achar o mesmo título no sumário e no texto: sem rótulo ("Capítulo 1 -"), sem número ("2.1 -"),
     * sem acento, sem pontuação no fim. "1 - Seja cheio do Espírito" e "Capítulo 1 - Seja cheio do Espírito" casam.
     */
    static String titleKey(String title) {
        String text = title == null ? "" : title.strip();
        Matcher label = LABEL.matcher(text);
        if (label.find()) {
            text = text.substring(label.end());
        } else {
            text = NUMBER_PREFIX.matcher(text).replaceFirst("");
        }
        String key = StructureAnalyzer.key(text).replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll(" +", " ").strip();
        return key.isEmpty() ? StructureAnalyzer.key(title) : key;
    }
}
