package com.booksmith.export.internal;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * A névoa atrás do texto do livro ilustrado: a arte clareia (ou escurece, atrás do texto claro) perto do texto e volta
 * ao normal com a borda esfumada, sem caixa. O motor do PDF não faz desfoque nem gradiente radial, então a névoa é um
 * PNG com transparência, esticado no fundo da caixa do texto (o centro, onde fica o texto, é o mais opaco).
 */
final class Haze {

    /** Névoa clara (atrás do texto escuro), em data URI. */
    static final String LIGHT = dataUri(0xFFFFFF, 0.86);
    /** Névoa escura (atrás do texto claro). */
    static final String DARK = dataUri(0x000000, 0.62);

    private static final int WIDTH = 480;
    private static final int HEIGHT = 240;
    /** Até onde (do centro à borda, de 0 a 1) a névoa é cheia, na horizontal e na vertical. */
    private static final double CORE_X = 0.62;
    private static final double CORE_Y = 0.42;

    private Haze() {
    }

    /** Transparência de 0 a 1 no ponto (u, v) da caixa, de 0 a 1: cheia no miolo, esfumando até zero na borda. */
    static double alpha(double u, double v, double max) {
        double ex = Math.max(0, (Math.abs(2 * u - 1) - CORE_X) / (1 - CORE_X));
        double ey = Math.max(0, (Math.abs(2 * v - 1) - CORE_Y) / (1 - CORE_Y));
        double d = Math.min(1, Math.sqrt(ex * ex + ey * ey));
        double smooth = d * d * (3 - 2 * d);
        return max * (1 - smooth);
    }

    private static String dataUri(int rgb, double max) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int a = (int) Math.round(255 * alpha((x + 0.5) / WIDTH, (y + 0.5) / HEIGHT, max));
                image.setRGB(x, y, (a << 24) | rgb);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }
}
