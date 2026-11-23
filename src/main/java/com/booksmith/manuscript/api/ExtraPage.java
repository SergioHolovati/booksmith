package com.booksmith.manuscript.api;

import com.booksmith.common.error.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Livro ilustrado: onde fica e como é o fundo de uma página que o Booksmith gera (folha de rosto, créditos,
 * dedicatória, sobre o autor…). O autor organiza essas páginas junto com as da história.
 *
 * @param key     qual página ({@link #KEYS}): {@code cover} (folha de rosto), {@code credits}, {@code title} (página de
 *                rosto), {@code dedication}, {@code epigraph}, {@code acknowledgments}, {@code about} (sobre o autor) e
 *                {@code colophon}
 * @param after   onde fica: {@link #START} (antes da história), {@link #END} (depois da história) ou o id da página da
 *                história que vem antes dela; null = o lugar de sempre (as da frente no começo, as do fim no fim)
 * @param imageId a ilustração de fundo só desta página (da biblioteca do universo); null = a das páginas geradas
 * @param color   a cor de fundo só desta página ({@code #rrggbb}); null = a das páginas geradas
 * @param id      só na página de ilustração ({@link #ILLUSTRATION}): qual é (pode haver várias)
 * @param bleed   só na página de ilustração: true = até a borda (cobre a página); senão inteira dentro das margens
 */
public record ExtraPage(String key, String after, String imageId, String color, String id, Boolean bleed) {

    /**
     * Página só com uma ilustração, em qualquer livro: no livro de texto, no começo (antes da folha de rosto,
     * {@link #START}), antes do primeiro capítulo ({@link #FRONT}), depois de um capítulo ou no fim ({@link #END}).
     */
    public static final String ILLUSTRATION = "illustration";
    /** Depois do sumário, antes do primeiro capítulo (só a página de ilustração). */
    public static final String FRONT = "front";
    /** Páginas de ilustração por livro. */
    public static final int MAX_ILLUSTRATIONS = 60;

    /** Uma das páginas geradas ({@link #KEYS}). */
    public ExtraPage(String key, String after, String imageId, String color) {
        this(key, after, imageId, color, null, null);
    }

    /** É página de ilustração (e não uma das geradas). */
    public boolean illustration() {
        return ILLUSTRATION.equals(key);
    }

    /** As páginas, na ordem de um miolo de editora. */
    public static final List<String> KEYS = List.of("cover", "credits", "title", "dedication", "epigraph",
            "acknowledgments", "about", "colophon");
    /** As que ficam no fim quando o autor não escolheu o lugar. */
    private static final List<String> BACK = List.of("about", "colophon");
    public static final String START = "start";
    public static final String END = "end";
    public static final int MAX = KEYS.size() + MAX_ILLUSTRATIONS;

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Valores limpos; vazio vira null.
     *
     * @throws ApiException 422 {@code extra_page_invalid} com página, lugar, ilustração ou cor fora do formato
     */
    public ExtraPage {
        if (key == null || !KEYS.contains(key) && !ILLUSTRATION.equals(key)) {
            throw invalid("Página desconhecida.");
        }
        after = blankToNull(after);
        boolean art = ILLUSTRATION.equals(key);
        if (after != null && !START.equals(after) && !END.equals(after) && !(art && FRONT.equals(after))
                && !UUID.matcher(after).matches()) {
            throw invalid("Lugar da página inválido.");
        }
        imageId = blankToNull(imageId);
        if (imageId != null && !UUID.matcher(imageId).matches()) {
            throw invalid("Ilustração de fundo inválida.");
        }
        color = blankToNull(color);
        if (color != null && !color.matches("^#[0-9a-fA-F]{6}$")) {
            throw invalid("Cor de fundo inválida (use #rrggbb).");
        }
        color = color == null ? null : color.toLowerCase(Locale.ROOT);
        id = blankToNull(id);
        if (art) {
            if (id == null || !UUID.matcher(id).matches() || imageId == null) {
                throw invalid("Página de ilustração sem a ilustração.");
            }
            id = id.toLowerCase(Locale.ROOT);
            color = null;
            bleed = Boolean.TRUE.equals(bleed);
        } else {
            id = null;
            bleed = null;
        }
    }

    /** O lugar de sempre desta página: as da frente antes da história, o "sobre" e o colofão depois. */
    public static String defaultAfter(String key) {
        return BACK.contains(key) ? END : START;
    }

    /** A mesma página em outro lugar. */
    public ExtraPage at(String place) {
        return new ExtraPage(key, place, imageId, color, id, bleed);
    }

    /**
     * As páginas que o livro tem ({@code present}, na ordem de {@link #KEYS}), na ordem e no lugar que o autor
     * escolheu ({@code stored}); a que ele ainda não mexeu entra no lugar de sempre, perto das vizinhas no miolo de
     * editora. Toda página sai com o lugar preenchido ({@code chapterIds}: as páginas da história, em minúsculas).
     */
    public static List<ExtraPage> arrange(List<ExtraPage> stored, List<String> present,
                                          java.util.Collection<String> chapterIds) {
        List<ExtraPage> result = new ArrayList<>();
        if (stored != null) {
            for (ExtraPage page : stored) {
                if (page.illustration()) {
                    // Depois de um capítulo que saiu do livro: vai para o fim
                    boolean gone = page.after() != null && UUID.matcher(page.after()).matches()
                            && !chapterIds.contains(page.after().toLowerCase(Locale.ROOT));
                    result.add(page.after() == null || gone ? page.at(END) : page);
                    continue;
                }
                if (present.contains(page.key()) && result.stream().noneMatch(p -> p.key().equals(page.key()))) {
                    // Depois de uma página que saiu do livro: volta ao lugar de sempre
                    boolean gone = page.after() != null && !START.equals(page.after()) && !END.equals(page.after())
                            && !chapterIds.contains(page.after().toLowerCase(Locale.ROOT));
                    result.add(page.after() == null || gone ? page.at(defaultAfter(page.key())) : page);
                }
            }
        }
        for (String key : KEYS) {
            if (!present.contains(key) || result.stream().anyMatch(p -> p.key().equals(key))) {
                continue;
            }
            String after = defaultAfter(key);
            int rank = KEYS.indexOf(key);
            int at = -1;
            for (int i = 0; i < result.size(); i++) {
                ExtraPage other = result.get(i);
                if (after.equals(other.after()) && KEYS.indexOf(other.key()) < rank) {
                    at = i + 1;
                }
            }
            if (at < 0) {
                // Sem vizinha antes: no começo do grupo (o da frente) ou no fim da lista (o do fim)
                at = START.equals(after) ? firstOf(result, after) : result.size();
            }
            result.add(at, new ExtraPage(key, after, null, null));
        }
        return result;
    }

    private static int firstOf(List<ExtraPage> pages, String after) {
        for (int i = 0; i < pages.size(); i++) {
            if (after.equals(pages.get(i).after())) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Confere a lista que o autor mandou (sem página repetida, no máximo uma de cada).
     *
     * @throws ApiException 422 {@code extra_page_invalid}
     */
    public static List<ExtraPage> validated(List<ExtraPage> pages) {
        if (pages == null) {
            return List.of();
        }
        List<ExtraPage> generated = pages.stream().filter(p -> !p.illustration()).toList();
        List<ExtraPage> art = pages.stream().filter(ExtraPage::illustration).toList();
        if (generated.size() > KEYS.size() || generated.stream().map(ExtraPage::key).distinct().count() != generated.size()) {
            throw invalid("Cada página gerada aparece uma vez só.");
        }
        if (art.size() > MAX_ILLUSTRATIONS) {
            throw invalid("No máximo " + MAX_ILLUSTRATIONS + " páginas de ilustração por livro.");
        }
        if (art.stream().map(ExtraPage::id).distinct().count() != art.size()) {
            throw invalid("Página de ilustração repetida.");
        }
        return List.copyOf(pages);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("extra_page_invalid", message);
    }
}
