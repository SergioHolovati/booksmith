package com.booksmith.manuscript.api;

import com.booksmith.common.error.ApiException;

import java.util.List;

/**
 * Diagramação do livro (§4.7 do produto): as escolhas que valem para o PDF. Campo ausente ou nulo usa o padrão
 * (o PDF de leitura de antes: A5, Literata 11, entrelinha normal, abertura clássica, "* * *").
 *
 * @param trim           tamanho da página ({@link #TRIMS}): em pé ({@code a5} 148×210 mm, {@code 14x21}, {@code 16x23},
 *                       {@code 6x9} polegadas), quadrado ({@code 15x15}, {@code 20x20}, {@code 21x21}, {@code 25x25},
 *                       {@code 8.5x8.5} e {@code 8.25x8.25} polegadas, os do KDP), deitado ({@code 21x15},
 *                       {@code 25x20}, {@code 28x21}, {@code 8.25x6} polegadas) ou {@code custom}
 * @param fontSize       corpo do texto, em pontos: 10, 10.5, 11, 11.5 ou 12
 * @param leading        entrelinha: {@code compact}, {@code normal} ou {@code airy}
 * @param margins        margens: {@code narrow}, {@code normal} ou {@code wide}
 * @param chapterStyle   abertura de capítulo: {@code classic} (título centralizado, abaixo do meio), {@code modern}
 *                       (título grande à esquerda, com fio) ou {@code simple} (título no alto, sem espaço extra)
 * @param sceneBreak     quebra de cena: {@code asterisks} (* * *), {@code asterism} (⁂), {@code diamond} (◆),
 *                       {@code dots} (• • •) ou {@code space} (só espaço em branco)
 * @param customWidthMm  largura do tamanho personalizado ({@code trim = custom}), de {@value #MIN_CUSTOM_MM} a
 *                       {@value #MAX_CUSTOM_MM} mm; nos outros tamanhos, null
 * @param customHeightMm altura do tamanho personalizado, idem
 * @param bleed          sangria do miolo para a gráfica: a página cresce {@value #BLEED_MM} mm na borda de fora e em
 *                       cima e embaixo (o padrão do KDP), para a ilustração até a borda não sair com filete branco
 * @param autoPages      livro ilustrado: o Booksmith gera a folha de rosto, os créditos e a dedicatória (padrão); sem
 *                       isso, o autor monta todas as páginas (capa ilustrada, créditos com ficha, dedicatória…)
 * @param pageNumbers    livro ilustrado: número de página pequeno no canto de fora (padrão: sem)
 */
public record BookLayout(String trim, Double fontSize, String leading, String margins, String chapterStyle,
                         String sceneBreak, Double customWidthMm, Double customHeightMm, Boolean bleed,
                         Boolean autoPages, Boolean pageNumbers) {

    public static final List<String> TRIMS = List.of("a5", "14x21", "16x23", "17x24", "6x9",
            "15x15", "20x20", "21x21", "25x25", "8.5x8.5", "8.25x8.25",
            "21x15", "25x20", "28x21", "8.25x6", "custom");
    public static final List<Double> FONT_SIZES = List.of(10.0, 10.5, 11.0, 11.5, 12.0);
    public static final List<String> LEADINGS = List.of("compact", "normal", "airy");
    public static final List<String> MARGINS = List.of("narrow", "normal", "wide");
    public static final List<String> CHAPTER_STYLES = List.of("classic", "modern", "simple");
    public static final List<String> SCENE_BREAKS = List.of("asterisks", "asterism", "diamond", "dots", "space");

    /** Sangria do miolo: 1/8 de polegada, o que o KDP pede (e serve às gráficas que pedem 3 mm). */
    public static final double BLEED_MM = 3.2;
    public static final int MIN_CUSTOM_MM = 100;
    public static final int MAX_CUSTOM_MM = 330;

    public static final BookLayout DEFAULT = new BookLayout(null, null, null, null, null, null);

    /** A diagramação sem tamanho personalizado nem sangria (a de antes). */
    public BookLayout(String trim, Double fontSize, String leading, String margins, String chapterStyle,
                      String sceneBreak) {
        this(trim, fontSize, leading, margins, chapterStyle, sceneBreak, null, null, null);
    }

    /** Sem as escolhas do livro ilustrado (páginas automáticas e número de página no padrão). */
    public BookLayout(String trim, Double fontSize, String leading, String margins, String chapterStyle,
                      String sceneBreak, Double customWidthMm, Double customHeightMm, Boolean bleed) {
        this(trim, fontSize, leading, margins, chapterStyle, sceneBreak, customWidthMm, customHeightMm, bleed, null,
                null);
    }

    /**
     * Nulo vira o padrão.
     *
     * @throws ApiException 422 {@code layout_invalid} com valor fora das opções
     */
    public BookLayout {
        trim = pick(trim, TRIMS, "Tamanho de página desconhecido.");
        fontSize = fontSize == null ? Double.valueOf(11.0) : fontSize;
        if (!FONT_SIZES.contains(fontSize)) {
            throw invalid("Tamanho de letra desconhecido. Use 10, 10,5, 11, 11,5 ou 12.");
        }
        leading = pick(leading, LEADINGS, "Entrelinha desconhecida.");
        margins = pick(margins, MARGINS, "Margem desconhecida.");
        chapterStyle = pick(chapterStyle, CHAPTER_STYLES, "Abertura de capítulo desconhecida.");
        sceneBreak = pick(sceneBreak, SCENE_BREAKS, "Quebra de cena desconhecida.");
        if ("custom".equals(trim)) {
            if (!inRange(customWidthMm) || !inRange(customHeightMm)) {
                throw invalid("No tamanho personalizado, informe a largura e a altura entre %d e %d mm."
                        .formatted(MIN_CUSTOM_MM, MAX_CUSTOM_MM));
            }
            customWidthMm = Math.round(customWidthMm * 10) / 10.0;
            customHeightMm = Math.round(customHeightMm * 10) / 10.0;
        } else {
            customWidthMm = null;
            customHeightMm = null;
        }
        bleed = Boolean.TRUE.equals(bleed);
        autoPages = !Boolean.FALSE.equals(autoPages);
        pageNumbers = Boolean.TRUE.equals(pageNumbers);
    }

    /** Livro ilustrado: o Booksmith gera a folha de rosto e as páginas da frente. */
    public boolean generatesPages() {
        return !Boolean.FALSE.equals(autoPages);
    }

    public boolean numbersPages() {
        return Boolean.TRUE.equals(pageNumbers);
    }

    private static boolean inRange(Double mm) {
        return mm != null && mm >= MIN_CUSTOM_MM && mm <= MAX_CUSTOM_MM;
    }

    /** Largura e altura da página em milímetros (o corte, sem a sangria). */
    public double widthMm() {
        return customWidthMm != null ? customWidthMm : trimWidthMm(trim);
    }

    public double heightMm() {
        return customHeightMm != null ? customHeightMm : trimHeightMm(trim);
    }

    /** Com sangria do miolo. */
    public boolean hasBleed() {
        return Boolean.TRUE.equals(bleed);
    }

    /** Largura de um tamanho de página ({@link #TRIMS}), em mm. O personalizado não tem medida fixa: usa a do A5. */
    public static double trimWidthMm(String trim) {
        return switch (trim) {
            case "14x21" -> 140;
            case "16x23" -> 160;
            case "17x24" -> 170;
            case "6x9" -> 152.4;
            case "15x15" -> 150;
            case "20x20" -> 200;
            case "21x21", "21x15" -> 210;
            case "25x25", "25x20" -> 250;
            case "28x21" -> 280;
            case "8.5x8.5" -> 215.9;
            case "8.25x8.25", "8.25x6" -> 209.6;
            default -> 148;
        };
    }

    public static double trimHeightMm(String trim) {
        return switch (trim) {
            case "16x23" -> 230;
            case "17x24" -> 240;
            case "6x9" -> 228.6;
            case "15x15", "21x15" -> 150;
            case "20x20", "25x20" -> 200;
            case "21x21" -> 210;
            case "25x25" -> 250;
            case "8.5x8.5" -> 215.9;
            case "8.25x8.25" -> 209.6;
            case "8.25x6" -> 152.4;
            default -> 210;
        };
    }

    private static String pick(String value, List<String> options, String message) {
        if (value == null) {
            return options.contains("normal") ? "normal" : options.getFirst();
        }
        if (!options.contains(value)) {
            throw invalid(message);
        }
        return value;
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("layout_invalid", message);
    }
}
