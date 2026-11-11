package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * O desenho da capa feita no editor do app (§4.7 do produto), guardado para poder ser editado de novo. A imagem
 * em si é desenhada no aparelho e guardada como a capa do livro; aqui ficam só as escolhas.
 *
 * @param template        {@code classic} (imagem inteira, título no alto), {@code band} (faixa de cor embaixo com o
 *                        título) ou {@code minimal} (cor lisa com moldura fina)
 * @param backgroundColor cor do fundo, {@code #rrggbb}
 * @param imageId         imagem da biblioteca do universo do livro no fundo; null = só a cor
 * @param shade           quanto a imagem escurece, de 0 a 70 (%), para o texto aparecer
 * @param font            {@code serif} (Literata) ou {@code sans} (Inter)
 * @param titleSize       {@code s}, {@code m} ou {@code l}
 * @param accentColor     cor da faixa e da moldura
 * @param backText        texto da contracapa da capa para gráfica (parágrafos separados por linha); null = sem texto
 */
public record CoverDesign(String template, String backgroundColor, UUID imageId, Integer shade, String title,
                          String subtitle, String author, String font, String titleSize, String textColor,
                          String accentColor, String backText) {

    static final int MAX_BACK_TEXT = 1500;

    /** Desenho sem texto de contracapa. */
    public CoverDesign(String template, String backgroundColor, UUID imageId, Integer shade, String title,
                       String subtitle, String author, String font, String titleSize, String textColor,
                       String accentColor) {
        this(template, backgroundColor, imageId, shade, title, subtitle, author, font, titleSize, textColor, accentColor,
                null);
    }

    static final List<String> TEMPLATES = List.of("classic", "band", "minimal");
    static final List<String> FONTS = List.of("serif", "sans");
    static final List<String> TITLE_SIZES = List.of("s", "m", "l");
    static final int MAX_SHADE = 70;

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** @throws ApiException 422 {@code cover_design_invalid} */
    public CoverDesign {
        template = option(template, TEMPLATES, "Modelo de capa desconhecido.");
        backgroundColor = color(backgroundColor, "#1f2a44");
        shade = shade == null ? Integer.valueOf(0) : shade;
        if (shade < 0 || shade > MAX_SHADE) {
            throw invalid("O escurecimento vai de 0 a " + MAX_SHADE + ".");
        }
        title = text(title, 200);
        if (title == null) {
            throw invalid("A capa precisa de um título.");
        }
        subtitle = text(subtitle, 200);
        author = text(author, 120);
        font = option(font, FONTS, "Letra desconhecida.");
        titleSize = titleSize == null ? "m" : option(titleSize, TITLE_SIZES, "Tamanho de título desconhecido.");
        textColor = color(textColor, "#ffffff");
        accentColor = color(accentColor, "#c8743a");
        backText = backText == null || backText.isBlank()
                ? null : backText.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (backText != null && backText.length() > MAX_BACK_TEXT) {
            throw invalid("O texto da contracapa passa de " + MAX_BACK_TEXT + " caracteres.");
        }
    }

    private static String option(String value, List<String> options, String message) {
        if (value == null) {
            return options.getFirst();
        }
        if (!options.contains(value)) {
            throw invalid(message);
        }
        return value;
    }

    private static String color(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (!COLOR.matcher(value).matches()) {
            throw invalid("Cor inválida: use o formato #rrggbb.");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').strip();
        if (clean.length() > max) {
            throw invalid("Texto da capa longo demais (até " + max + " caracteres).");
        }
        return clean;
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("cover_design_invalid", message);
    }
}
