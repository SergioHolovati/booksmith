package com.booksmith.manuscript.api;

import com.booksmith.common.error.ApiException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * O desenho de uma página do livro ilustrado (infantil): no livro infantil, cada capítulo é uma página. A ilustração
 * de fundo cobre a página até a borda (com a sangria, se houver); o texto do capítulo fica numa caixa por cima, dentro
 * da área segura. Campo nulo usa o padrão.
 *
 * @param imageId    a ilustração de fundo (da biblioteca do universo do livro); null = só a cor
 * @param background cor de fundo ({@code #rrggbb}), aparece onde não há imagem; padrão branco
 * @param text       onde fica o texto: {@code top}, {@code middle}, {@code bottom} (padrão), {@code free} (onde o
 *                   autor arrastou: {@code x}, {@code y}, {@code w}) ou {@code none} (página só de imagem)
 * @param align      {@code left}, {@code center} (padrão) ou {@code right}
 * @param size       tamanho da letra: {@code s} (16 pt), {@code m} (20), {@code l} (26, padrão) ou {@code xl} (34)
 * @param color      cor do texto: {@code dark} (padrão) ou {@code light}
 * @param band       faixa atrás do texto (clara com letra escura, escura com letra clara), para ler sobre a imagem
 * @param spread     a ilustração ocupa esta página e a seguinte (o livro aberto); a dupla começa numa página da esquerda
 * @param textSide   na dupla, em que página fica o texto: {@code left} ou {@code right} (padrão)
 * @param x          no texto livre, a borda esquerda da caixa, em % da largura da página (na dupla, do livro aberto)
 * @param y          no texto livre, o topo da caixa, em % da altura da página
 * @param w          no texto livre, a largura da caixa, em % da largura da página (na dupla, do livro aberto)
 */
public record PageDesign(String imageId, String background, String text, String align, String size, String color,
                         Boolean band, Boolean spread, String textSide, Double x, Double y, Double w) {

    public static final List<String> TEXTS = List.of("top", "middle", "bottom", "free", "none");
    public static final List<String> ALIGNS = List.of("left", "center", "right");
    public static final List<String> SIZES = List.of("s", "m", "l", "xl");
    public static final List<String> COLORS = List.of("dark", "light");
    public static final List<String> SIDES = List.of("left", "right");

    public static final PageDesign DEFAULT = new PageDesign(null, null, null, null, null, null, null, null, null);

    /** Sem posição livre (texto em cima, no meio ou embaixo). */
    public PageDesign(String imageId, String background, String text, String align, String size, String color,
                      Boolean band, Boolean spread, String textSide) {
        this(imageId, background, text, align, size, color, band, spread, textSide, null, null, null);
    }

    private static final Pattern UUID_TEXT = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** @throws ApiException 422 {@code page_invalid} com valor fora das opções */
    public PageDesign {
        if (imageId != null && imageId.isBlank()) {
            imageId = null;
        }
        if (imageId != null && !UUID_TEXT.matcher(imageId).matches()) {
            throw invalid("Ilustração de fundo inválida.");
        }
        if (background != null && !HEX.matcher(background).matches()) {
            throw invalid("Cor de fundo inválida (use #rrggbb).");
        }
        background = background == null ? "#ffffff" : background.toLowerCase(java.util.Locale.ROOT);
        text = pick(text, TEXTS, "bottom", "Posição do texto desconhecida.");
        align = pick(align, ALIGNS, "center", "Alinhamento desconhecido.");
        size = pick(size, SIZES, "l", "Tamanho de letra desconhecido.");
        color = pick(color, COLORS, "dark", "Cor do texto desconhecida.");
        band = Boolean.TRUE.equals(band);
        spread = Boolean.TRUE.equals(spread);
        textSide = pick(textSide, SIDES, "right", "Lado do texto desconhecido.");
        // A caixa cabe na página: largura de 15% a 100%, e a borda esquerda e o topo de 0% até onde ela ainda cabe
        w = w == null ? Double.valueOf(80) : Double.valueOf(clamp(w, 15, 100));
        x = x == null ? Double.valueOf(10) : Double.valueOf(clamp(x, 0, 100 - w));
        y = y == null ? Double.valueOf(10) : Double.valueOf(clamp(y, 0, 95));
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            throw invalid("Posição do texto inválida.");
        }
        return Math.round(Math.max(min, Math.min(max, value)) * 10) / 10.0;
    }

    /** Corpo da letra em pontos. */
    public double fontSizePt() {
        return switch (size) {
            case "s" -> 16;
            case "m" -> 20;
            case "xl" -> 34;
            default -> 26;
        };
    }

    public boolean isSpread() {
        return Boolean.TRUE.equals(spread);
    }

    public boolean hasBand() {
        return Boolean.TRUE.equals(band);
    }

    private static String pick(String value, List<String> options, String fallback, String message) {
        if (value == null) {
            return fallback;
        }
        if (!options.contains(value)) {
            throw invalid(message);
        }
        return value;
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("page_invalid", message);
    }
}
