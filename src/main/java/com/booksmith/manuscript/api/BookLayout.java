package com.booksmith.manuscript.api;

import com.booksmith.common.error.ApiException;

import java.util.List;

/**
 * Diagramação do livro (§4.7 do produto): as escolhas que valem para o PDF. Campo ausente ou nulo usa o padrão
 * (o PDF de leitura de antes: A5, Literata 11, entrelinha normal, abertura clássica, "* * *").
 *
 * @param trim         tamanho da página: {@code a5} (148×210 mm), {@code 14x21}, {@code 16x23} ou {@code 6x9} (polegadas)
 * @param fontSize     corpo do texto, em pontos: 10, 10.5, 11, 11.5 ou 12
 * @param leading      entrelinha: {@code compact}, {@code normal} ou {@code airy}
 * @param margins      margens: {@code narrow}, {@code normal} ou {@code wide}
 * @param chapterStyle abertura de capítulo: {@code classic} (título centralizado, abaixo do meio), {@code modern}
 *                     (título grande à esquerda, com fio) ou {@code simple} (título no alto, sem espaço extra)
 * @param sceneBreak   quebra de cena: {@code asterisks} (* * *), {@code asterism} (⁂), {@code diamond} (◆),
 *                     {@code dots} (• • •) ou {@code space} (só espaço em branco)
 */
public record BookLayout(String trim, Double fontSize, String leading, String margins, String chapterStyle,
                         String sceneBreak) {

    public static final List<String> TRIMS = List.of("a5", "14x21", "16x23", "6x9");
    public static final List<Double> FONT_SIZES = List.of(10.0, 10.5, 11.0, 11.5, 12.0);
    public static final List<String> LEADINGS = List.of("compact", "normal", "airy");
    public static final List<String> MARGINS = List.of("narrow", "normal", "wide");
    public static final List<String> CHAPTER_STYLES = List.of("classic", "modern", "simple");
    public static final List<String> SCENE_BREAKS = List.of("asterisks", "asterism", "diamond", "dots", "space");

    public static final BookLayout DEFAULT = new BookLayout(null, null, null, null, null, null);

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
    }

    /** Largura e altura da página em milímetros. */
    public double widthMm() {
        return trimWidthMm(trim);
    }

    public double heightMm() {
        return trimHeightMm(trim);
    }

    /** Largura de um tamanho de página ({@link #TRIMS}), em mm. */
    public static double trimWidthMm(String trim) {
        return switch (trim) {
            case "14x21" -> 140;
            case "16x23" -> 160;
            case "6x9" -> 152.4;
            default -> 148;
        };
    }

    public static double trimHeightMm(String trim) {
        return switch (trim) {
            case "16x23" -> 230;
            case "6x9" -> 228.6;
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
