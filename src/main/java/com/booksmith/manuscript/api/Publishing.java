package com.booksmith.manuscript.api;

import com.booksmith.common.error.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * O guia de publicação do livro (§4.7 do produto): para onde o livro vai, os ISBNs, as palavras-chave da loja e os
 * passos que o autor já marcou como feitos. Tudo opcional; os ISBNs saem na página de créditos (o do impresso no
 * PDF, o do e-book no ePub) e o do e-book vira o identificador do ePub.
 *
 * @param destination {@code kdp} (Amazon), {@code clube} (Clube de Autores), {@code uiclap} ou {@code grafica}
 *                    (gráfica própria); null = ainda não escolheu
 * @param isbnPrint   ISBN do livro impresso, como o autor escreveu (com ou sem hífens), conferido pelo dígito
 * @param isbnEbook   ISBN do e-book (cada formato tem o seu)
 * @param keywords    palavras-chave da loja, até {@value #MAX_KEYWORDS} (o KDP tem 7 campos)
 * @param done        passos feitos à mão, de {@link #STEPS}
 */
public record Publishing(String destination, String isbnPrint, String isbnEbook, List<String> keywords,
                         Set<String> done) {

    public static final List<String> DESTINATIONS = List.of("kdp", "clube", "uiclap", "grafica");

    /**
     * Passos que o app não tem como conferir: conta na plataforma, ISBN grátis do KDP, ficha catalográfica, envio
     * dos arquivos, prova impressa, preço e o depósito legal na Biblioteca Nacional.
     */
    public static final List<String> STEPS = List.of("conta", "isbn-kdp", "ficha", "envio", "prova", "preco",
            "deposito");

    public static final int MAX_KEYWORDS = 7;
    public static final int MAX_KEYWORD_LENGTH = 50;

    public static final Publishing EMPTY = new Publishing(null, null, null, List.of(), Set.of());

    /**
     * @throws ApiException 422 {@code publishing_invalid} com destino ou passo desconhecido, ISBN que não confere
     *                      ou palavras-chave demais
     */
    public Publishing {
        destination = blankToNull(destination);
        if (destination != null && !DESTINATIONS.contains(destination)) {
            throw invalid("Destino desconhecido.");
        }
        isbnPrint = isbn(isbnPrint, "do livro impresso");
        isbnEbook = isbn(isbnEbook, "do e-book");
        if (isbnPrint != null && isbnEbook != null && digits(isbnPrint).equals(digits(isbnEbook))) {
            throw invalid("O livro impresso e o e-book precisam de ISBNs diferentes: cada formato tem o seu.");
        }
        keywords = keywords(keywords);
        Set<String> steps = new LinkedHashSet<>();
        for (String step : done == null ? Set.<String>of() : done) {
            if (!STEPS.contains(step)) {
                throw invalid("Passo desconhecido: " + step + ".");
            }
            steps.add(step);
        }
        done = Set.copyOf(steps);
    }

    /** Só os números do ISBN ("978-65-00-12345-6" → "9786500123456"), para o ePub. */
    public static String digits(String isbn) {
        return isbn == null ? null : isbn.replaceAll("[^0-9]", "");
    }

    /** ISBN-13 com o dígito verificador certo (pesos 1 e 3, alternados). */
    public static boolean validIsbn(String isbn) {
        if (isbn == null || !isbn.matches("[0-9][0-9 -]*[0-9]")) {
            return false;
        }
        String digits = digits(isbn);
        if (digits.length() != 13 || !(digits.startsWith("978") || digits.startsWith("979"))) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            sum += (digits.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }

    private static String isbn(String value, String which) {
        String isbn = blankToNull(value);
        if (isbn == null) {
            return null;
        }
        isbn = isbn.replaceFirst("(?i)^isbn[:\\s]*", "").strip();
        if (!validIsbn(isbn)) {
            throw invalid("O ISBN " + which + " não confere. São 13 números começando com 978 ou 979 "
                    + "(ex.: 978-65-00-12345-6); confira se nenhum número ficou trocado.");
        }
        return isbn;
    }

    private static List<String> keywords(List<String> values) {
        List<String> clean = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String keyword = blankToNull(value);
            if (keyword == null || !seen.add(keyword.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (keyword.length() > MAX_KEYWORD_LENGTH) {
                throw invalid("Cada palavra-chave vai até " + MAX_KEYWORD_LENGTH + " caracteres.");
            }
            clean.add(keyword);
        }
        if (clean.size() > MAX_KEYWORDS) {
            throw invalid("São até " + MAX_KEYWORDS + " palavras-chave.");
        }
        return List.copyOf(clean);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("publishing_invalid", message);
    }
}
