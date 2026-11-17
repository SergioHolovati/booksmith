package com.booksmith.export.internal;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * O desfoque atrás do texto do livro ilustrado: o pedaço da ilustração atrás do texto (com uma margem) desfocado, um
 * pouco clareado (ou escurecido, atrás do texto claro) e com a borda esfumada, posto por cima da arte e embaixo do
 * texto. O motor do PDF não desfoca, então o pedaço é desenhado aqui e entra como imagem.
 *
 * <p>Medidas em mm, a partir do canto da página (a mesma origem da arte e da caixa do texto).
 */
final class BlurPatch {

    /** Margem do desfoque em volta do texto, em em (a mesma do app: o ::before com inset de 0,9em por 1,2em). */
    static final double MARGIN_X_EM = 1.2;
    static final double MARGIN_Y_EM = 0.9;
    /** Resolução do pedaço desfocado (não precisa de mais: está borrado). */
    private static final double PX_PER_MM = 5;
    /** O desfoque: a imagem reduzida a 1 px por mm e borrada (três passadas de caixa de raio 2 ≈ 2,5 mm). */
    private static final int BLUR_RADIUS = 2;
    private static final int BLUR_PASSES = 3;
    /** Quanto o pedaço vai na direção do branco (texto escuro) ou do preto (texto claro). */
    private static final double TINT = 0.25;
    private static final double MM_PER_PT = 25.4 / 72;
    private static final double LINE_HEIGHT = 1.3;
    private static final double PARAGRAPH_GAP = 0.35;
    /** Largura média de um caractere, em em (para estimar quantas linhas o texto ocupa). */
    private static final double CHAR_EM = 0.55;

    private BlurPatch() {
    }

    /** A altura estimada do texto (parágrafos {@code <p>} do HTML), em mm, numa caixa dessa largura. */
    static double textHeightMm(String html, double widthMm, double fontPt) {
        double em = fontPt * MM_PER_PT;
        int perLine = Math.max(1, (int) Math.floor(widthMm / (CHAR_EM * em)));
        int lines = 0;
        int paragraphs = 0;
        for (String part : html.split("</p>")) {
            String plain = part.replaceAll("<[^>]+>", "").replace("&#160;", " ").replaceAll("&[a-z#0-9]+;", "x").strip();
            if (!plain.isEmpty()) {
                paragraphs++;
                lines += (int) Math.ceil(plain.length() / (double) perLine);
            }
        }
        return lines == 0 ? 0 : (lines * LINE_HEIGHT + Math.max(0, paragraphs - 1) * PARAGRAPH_GAP) * em;
    }

    /**
     * O {@code <img>} do pedaço desfocado.
     *
     * @param art     a ilustração (bytes)
     * @param artMm   onde a arte está na página: esquerda, topo, largura, altura
     * @param textMm  a caixa do texto: esquerda, topo, largura, altura
     * @param pageMm  largura e altura da página
     * @param fontPt  corpo da letra (a margem acompanha)
     * @param lightTint texto escuro: clarear; texto claro: escurecer
     * @param background a cor de fundo da página (onde a arte não cobre)
     */
    static String html(byte[] art, double[] artMm, double[] textMm, double[] pageMm, double fontPt, boolean lightTint,
                       String background) {
        BufferedImage source = read(art);
        if (source == null || textMm[2] <= 0 || textMm[3] <= 0) {
            return "";
        }
        double em = fontPt * MM_PER_PT;
        double mx = MARGIN_X_EM * em;
        double my = MARGIN_Y_EM * em;
        double left = Math.max(0, textMm[0] - mx);
        double top = Math.max(0, textMm[1] - my);
        double right = Math.min(pageMm[0], textMm[0] + textMm[2] + mx);
        double bottom = Math.min(pageMm[1], textMm[1] + textMm[3] + my);
        double w = right - left;
        double h = bottom - top;
        if (w <= 1 || h <= 1) {
            return "";
        }
        BufferedImage blurred = blurred(source, artMm, left, top, w, h, background);
        BufferedImage patch = feathered(blurred, w, h, new boolean[] {left > 0, top > 0, right < pageMm[0],
                bottom < pageMm[1]}, mx, my, lightTint);
        return "<img class=\"pb-blur\" src=\"%s\" alt=\"\" style=\"left: %smm; top: %smm; width: %smm; height: %smm\"/>"
                .formatted(png(patch), mm(left), mm(top), mm(w), mm(h));
    }

    /** O pedaço (left, top, w, h) da página, com a arte onde ela está, reduzido a 1 px por mm, borrado e ampliado. */
    private static BufferedImage blurred(BufferedImage source, double[] artMm, double left, double top, double w,
                                         double h, String background) {
        int sw = Math.max(4, (int) Math.round(w));
        int sh = Math.max(4, (int) Math.round(h));
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.decode(background));
        g.fillRect(0, 0, sw, sh);
        double sx = sw / w;
        double sy = sh / h;
        g.drawImage(source, (int) Math.round((artMm[0] - left) * sx), (int) Math.round((artMm[1] - top) * sy),
                (int) Math.round(artMm[2] * sx), (int) Math.round(artMm[3] * sy), null);
        g.dispose();
        for (int i = 0; i < BLUR_PASSES; i++) {
            small = boxBlur(small);
        }
        int ow = Math.max(8, (int) Math.round(w * PX_PER_MM));
        int oh = Math.max(8, (int) Math.round(h * PX_PER_MM));
        BufferedImage out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D big = out.createGraphics();
        big.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        big.drawImage(small, 0, 0, ow, oh, null);
        big.dispose();
        return out;
    }

    /** Uma passada de desfoque de caixa (horizontal e vertical), com a borda repetida. */
    static BufferedImage boxBlur(BufferedImage in) {
        int w = in.getWidth();
        int h = in.getHeight();
        int[] src = in.getRGB(0, 0, w, h, null, 0, w);
        int[] tmp = new int[src.length];
        int[] dst = new int[src.length];
        pass(src, tmp, w, h, true);
        pass(tmp, dst, w, h, false);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, dst, 0, w);
        return out;
    }

    private static void pass(int[] in, int[] out, int w, int h, boolean horizontal) {
        int n = 2 * BLUR_RADIUS + 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0;
                int gr = 0;
                int b = 0;
                for (int k = -BLUR_RADIUS; k <= BLUR_RADIUS; k++) {
                    int xx = horizontal ? Math.clamp(x + k, 0, w - 1) : x;
                    int yy = horizontal ? y : Math.clamp(y + k, 0, h - 1);
                    int p = in[yy * w + xx];
                    r += (p >> 16) & 0xff;
                    gr += (p >> 8) & 0xff;
                    b += p & 0xff;
                }
                out[y * w + x] = (r / n << 16) | (gr / n << 8) | (b / n);
            }
        }
    }

    /**
     * A borda esfumada (some em direção à borda, ao longo da margem; do lado em que o pedaço encosta na borda da
     * página, não esfuma) e o tom (clarear ou escurecer um pouco, para o texto ler bem).
     *
     * @param sides se esfuma à esquerda, em cima, à direita, embaixo
     */
    private static BufferedImage feathered(BufferedImage image, double w, double h, boolean[] sides, double mx,
                                           double my, boolean lightTint) {
        int ow = image.getWidth();
        int oh = image.getHeight();
        int tint = lightTint ? 255 : 0;
        for (int y = 0; y < oh; y++) {
            double ymm = (y + 0.5) / oh * h;
            double ay = Math.min(sides[1] ? ramp(ymm / my) : 1, sides[3] ? ramp((h - ymm) / my) : 1);
            for (int x = 0; x < ow; x++) {
                double xmm = (x + 0.5) / ow * w;
                double ax = Math.min(sides[0] ? ramp(xmm / mx) : 1, sides[2] ? ramp((w - xmm) / mx) : 1);
                int p = image.getRGB(x, y);
                int r = mix((p >> 16) & 0xff, tint);
                int g = mix((p >> 8) & 0xff, tint);
                int b = mix(p & 0xff, tint);
                int a = (int) Math.round(255 * ax * ay);
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    /** De 0 (na borda) a 1 (depois da margem), suave. */
    static double ramp(double t) {
        double c = Math.clamp(t, 0, 1);
        return c * c * (3 - 2 * c);
    }

    private static int mix(int channel, int tint) {
        return (int) Math.round(channel + (tint - channel) * TINT);
    }

    private static BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    private static String png(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String mm(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }
}
