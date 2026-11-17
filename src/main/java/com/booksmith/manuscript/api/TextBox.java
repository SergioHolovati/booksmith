package com.booksmith.manuscript.api;

/**
 * Um bloco de texto a mais na página do livro ilustrado (o primeiro bloco é o do próprio {@link PageDesign}). No texto
 * da página, os blocos são separados pela quebra de bloco (o nó {@code sceneBreak}): o trecho antes da primeira quebra
 * vai no primeiro bloco, o seguinte no segundo, e assim por diante. Bloco a mais fica sempre onde o autor arrastou
 * ({@code x}, {@code y}, {@code w} em % da página, como o texto livre). Os campos e os padrões são os do
 * {@link PageDesign}; o construtor confere pelo mesmo caminho.
 */
public record TextBox(Double x, Double y, Double w, String size, String align, String color, String font,
                      Boolean band, Boolean haze, Boolean blur, Double backdropHeight) {

    /** Confere e põe os padrões (422 {@code page_invalid} com valor fora das opções). */
    public TextBox {
        PageDesign check = new PageDesign(null, null, "free", align, size, color, band, false, null, x, y, w, null,
                null, null, font, haze, blur, backdropHeight, null);
        x = check.x();
        y = check.y();
        w = check.w();
        size = check.size();
        align = check.align();
        color = check.color();
        font = check.font();
        band = check.band();
        haze = check.haze();
        blur = check.blur();
        backdropHeight = check.backdropHeight();
    }
}
