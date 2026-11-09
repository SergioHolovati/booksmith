package com.booksmith.continuity.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilidades de texto das verificações: frases, palavras com posição e citação curta. */
final class TextScan {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");
    static final int MAX_QUOTE = 280;

    private TextScan() {
    }

    record Word(String text, int start, int end) {

        String lower() {
            return text.toLowerCase(Locale.forLanguageTag("pt-BR"));
        }
    }

    static List<String> sentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(text)) {
            if (!sentence.isBlank()) {
                sentences.add(sentence.strip());
            }
        }
        return sentences;
    }

    static List<Word> words(String text) {
        List<Word> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            words.add(new Word(matcher.group(), matcher.start(), matcher.end()));
        }
        return words;
    }

    /** Citação de até {@link #MAX_QUOTE} caracteres em volta de {@code focus}, cortada em palavra. */
    static String quote(String sentence, int focus) {
        if (sentence.length() <= MAX_QUOTE) {
            return sentence;
        }
        int start = Math.max(0, focus - MAX_QUOTE / 2);
        int end = Math.min(sentence.length(), start + MAX_QUOTE);
        start = Math.max(0, end - MAX_QUOTE);
        int space = sentence.indexOf(' ', start);
        if (start > 0 && space > 0 && space < start + 20) {
            start = space + 1;
        }
        int lastSpace = sentence.lastIndexOf(' ', end);
        if (end < sentence.length() && lastSpace > end - 20) {
            end = lastSpace;
        }
        return (start > 0 ? "…" : "") + sentence.substring(start, end).strip() + (end < sentence.length() ? "…" : "");
    }

    /** Minúsculas e sem acento, para comparar palavras ("Âmbar" = "ambar"). */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }

    /** Distância de edição com transposição (Damerau, versão "optimal string alignment"). */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
