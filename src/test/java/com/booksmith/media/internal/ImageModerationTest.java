package com.booksmith.media.internal;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageModerationTest {

    /** O modelo baixado no notebook (tools/models); no servidor, ele vem na imagem da API. */
    private static final Path MODEL = Path.of("..", "tools", "models", "nsfw-vit-int8.onnx");
    private static final Path CLIP = Path.of("..", "tools", "models", "clip-vision-int8.onnx");

    private static final ModerationProperties LIMITS = new ModerationProperties(null, false, 0.80, 0.97, null, 1.1, 1.1);

    @Test
    void theScoreDecidesWhatHappensToTheImage() {
        assertThat(ImageModeration.decide(0.10, LIMITS)).isEqualTo(ImageModeration.Decision.OK);
        assertThat(ImageModeration.decide(0.80, LIMITS)).isEqualTo(ImageModeration.Decision.REVIEW);
        assertThat(ImageModeration.decide(0.969, LIMITS)).isEqualTo(ImageModeration.Decision.REVIEW);
        assertThat(ImageModeration.decide(0.97, LIMITS)).isEqualTo(ImageModeration.Decision.BLOCK);
    }

    @Test
    void theModelAnswerBecomesAProbability() {
        assertThat(NsfwClassifier.nsfw(new float[] {0, 0})).isCloseTo(0.5, within(1e-9));
        assertThat(NsfwClassifier.nsfw(new float[] {5, -5})).isLessThan(0.001);
        assertThat(NsfwClassifier.nsfw(new float[] {-5, 5})).isGreaterThan(0.999);
        // Números grandes não estouram (softmax com o máximo subtraído)
        assertThat(NsfwClassifier.nsfw(new float[] {1000, 990})).isCloseTo(0.0000454, within(1e-6));
    }

    @Test
    void pixelsAreNormalizedChannelByChannel() {
        BufferedImage red = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = red.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 10, 10);
        g.dispose();
        float[] pixels = NsfwClassifier.pixels(red);
        int plane = NsfwClassifier.SIZE * NsfwClassifier.SIZE;
        assertThat(pixels).hasSize(3 * plane);
        assertThat(pixels[0]).isEqualTo(1f);
        assertThat(pixels[plane]).isEqualTo(-1f);
        assertThat(pixels[2 * plane]).isEqualTo(-1f);
    }

    @Test
    void withoutTheModelNothingIsCheckedInDevelopmentButProductionRefusesToStart() {
        var off = new ImageModeration(new ModerationProperties("", false, 0.8, 0.97, null, 1.1, 1.1));
        assertThat(off.enabled()).isFalse();
        assertThat(off.check(new byte[] {1, 2, 3}).decision()).isEqualTo(ImageModeration.Decision.UNCHECKED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ImageModeration(new ModerationProperties("/nao/existe.onnx", true, 0.8, 0.97, null, 1.1, 1.1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ordinaryBookImagesPassAndAnUnreadableFileIsBlocked() throws Exception {
        assumeTrue(Files.exists(MODEL), "modelo de moderação não baixado em tools/models");
        var moderation = new ImageModeration(new ModerationProperties(MODEL.toString(), true, 0.80, 0.97, null, 1.1, 1.1));
        assertThat(moderation.enabled()).isTrue();
        try {
            for (byte[] image : new byte[][] {gradient(), page()}) {
                ImageModeration.Verdict verdict = moderation.check(image);
                assertThat(verdict.decision()).isEqualTo(ImageModeration.Decision.OK);
                assertThat(verdict.score()).isLessThan(0.5);
            }
            // Bytes que dizem ser imagem mas não abrem: sem poder conferir, não entra
            assertThat(moderation.check(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0}).decision())
                    .isEqualTo(ImageModeration.Decision.BLOCK);
        } finally {
            moderation.close();
        }
    }

    @Test
    void violenceAndHateOnlyEverSendToReviewWithTheReason() {
        var limits = new ModerationProperties(null, false, 0.80, 0.97, null, 0.5, 0.5);
        assertThat(ImageModeration.decide(0.1, 0.1, 0.9, limits))
                .isEqualTo(new ImageModeration.Verdict(ImageModeration.Decision.REVIEW, 0.9, "ódio"));
        assertThat(ImageModeration.decide(0.1, 0.99, 0.0, limits))
                .isEqualTo(new ImageModeration.Verdict(ImageModeration.Decision.REVIEW, 0.99, "violência"));
        // Nudez acima do bloqueio vale mais que tudo; abaixo, nada passa de revisão
        assertThat(ImageModeration.decide(0.98, 0.99, 0.99, limits).decision()).isEqualTo(ImageModeration.Decision.BLOCK);
        assertThat(ImageModeration.decide(0.85, 0, 0, limits).reason()).isEqualTo("nudez");
        assertThat(ImageModeration.decide(0.2, 0.2, 0.2, limits).decision()).isEqualTo(ImageModeration.Decision.OK);
    }

    @Test
    void aHateSymbolGoesToReviewAndOrdinaryImagesPass() throws Exception {
        assumeTrue(Files.exists(MODEL) && Files.exists(CLIP), "modelos de moderação não baixados em tools/models");
        var moderation = new ImageModeration(new ModerationProperties(MODEL.toString(), true, 0.80, 0.97,
                CLIP.toString(), 0.8, 0.9));
        try {
            ImageModeration.Verdict symbol = moderation.check(swastika());
            assertThat(symbol.decision()).isEqualTo(ImageModeration.Decision.REVIEW);
            assertThat(symbol.reason()).isEqualTo("ódio");
            assertThat(moderation.check(gradient()).decision()).isEqualTo(ImageModeration.Decision.OK);
            assertThat(moderation.check(page()).decision()).isEqualTo(ImageModeration.Decision.OK);
        } finally {
            moderation.close();
        }
    }

    /** Uma suástica numa bandeira, desenhada aqui (para conferir que o símbolo de ódio vai para a revisão). */
    private static byte[] swastika() throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 30, 30));
        g.fillRect(0, 0, 400, 400);
        g.setColor(Color.WHITE);
        g.fillOval(60, 60, 280, 280);
        g.setColor(Color.BLACK);
        int[][] bars = {{185, 110, 30, 180}, {110, 185, 180, 30}, {185, 110, 75, 30}, {260, 185, 30, 75},
            {140, 260, 75, 30}, {110, 140, 30, 75}};
        for (int[] bar : bars) {
            g.fillRect(bar[0], bar[1], bar[2], bar[3]);
        }
        g.dispose();
        return png(image);
    }

    /** Um céu de fim de tarde (fundo de capa). */
    private static byte[] gradient() throws Exception {
        BufferedImage image = new BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(30, 40, 90), 0, 1200, new Color(240, 140, 60)));
        g.fillRect(0, 0, 800, 1200);
        g.dispose();
        return png(image);
    }

    /** Uma página de texto. */
    private static byte[] page() throws Exception {
        BufferedImage image = new BufferedImage(600, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(244, 241, 234));
        g.fillRect(0, 0, 600, 900);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 22));
        for (int line = 0; line < 30; line++) {
            g.drawString("Era uma vez um farol no fim do mundo, aceso toda noite.", 40, 60 + line * 27);
        }
        g.dispose();
        return png(image);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
