package com.booksmith.manuscript.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * As fontes do texto do livro (todas livres, licença OFL, em {@code export/fonts}): as de leitura, para o miolo de
 * romance, devocional e autoajuda, e as infantis, para o livro ilustrado. A mesma lista vale no app (prévia) e no
 * livro baixado (PDF embute; ePub embute quando não é a padrão; Word usa o nome).
 *
 * @param id       o valor guardado ({@link BookLayout#font()}, {@link PageDesign#font()})
 * @param family   o nome da família (CSS, Word)
 * @param file     o começo do nome dos arquivos ({@code <file>-Regular.ttf}, {@code -Bold}, {@code -Italic},
 *                 {@code -BoldItalic})
 * @param weights  quais arquivos existem: 1 = só regular, 2 = regular e negrito, 4 = com itálicos
 */
public enum BookFont {
    LITERATA("literata", "Literata", "Literata", 4, false),
    LORA("lora", "Lora", "Lora", 4, false),
    EB_GARAMOND("eb-garamond", "EB Garamond", "EBGaramond", 4, false),
    MERRIWEATHER("merriweather", "Merriweather", "Merriweather", 4, false),
    ATKINSON("atkinson", "Atkinson Hyperlegible", "AtkinsonHyperlegible", 4, false),
    LEXEND("lexend", "Lexend", "Lexend", 2, false),
    ANDIKA("andika", "Andika", "Andika", 4, true),
    NUNITO("nunito", "Nunito", "Nunito", 4, true),
    FREDOKA("fredoka", "Fredoka", "Fredoka", 2, true),
    BALOO("baloo", "Baloo 2", "Baloo2", 2, true),
    PATRICK_HAND("patrick-hand", "Patrick Hand", "PatrickHand", 1, true),
    COMIC_NEUE("comic-neue", "Comic Neue", "ComicNeue", 4, true);

    public static final BookFont DEFAULT = LITERATA;

    private final String id;
    private final String family;
    private final String file;
    private final int weights;
    private final boolean children;

    BookFont(String id, String family, String file, int weights, boolean children) {
        this.id = id;
        this.family = family;
        this.file = file;
        this.weights = weights;
        this.children = children;
    }

    public String id() {
        return id;
    }

    public String family() {
        return family;
    }

    /** Fonte pensada para o livro infantil (letra de escola, arredondada ou manuscrita). */
    public boolean children() {
        return children;
    }

    public boolean hasBold() {
        return weights >= 2;
    }

    public boolean hasItalic() {
        return weights >= 4;
    }

    /** O arquivo do estilo: {@code Regular}, {@code Bold}, {@code Italic} ou {@code BoldItalic}. */
    public String fileName(String style) {
        return file + "-" + style + ".ttf";
    }

    public static Optional<BookFont> of(String id) {
        return Arrays.stream(values()).filter(font -> font.id.equals(id)).findFirst();
    }

    /** A fonte do id; nulo ou desconhecido = a padrão. */
    public static BookFont orDefault(String id) {
        return of(id).orElse(DEFAULT);
    }
}
