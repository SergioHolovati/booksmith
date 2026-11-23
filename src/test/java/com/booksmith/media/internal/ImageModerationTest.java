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

    private static final ModerationProperties LIMITS = new ModerationProperties(null, false, 0.80, 0.97);

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
        var off = new ImageModeration(new ModerationProperties("", false, 0.8, 0.97));
        assertThat(off.enabled()).isFalse();
        assertThat(off.check(new byte[] {1, 2, 3}).decision()).isEqualTo(ImageModeration.Decision.UNCHECKED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ImageModeration(new ModerationProperties("/nao/existe.onnx", true, 0.8, 0.97)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ordinaryBookImagesPassAndAnUnreadableFileIsBlocked() throws Exception {
        assumeTrue(Files.exists(MODEL), "modelo de moderação não baixado em tools/models");
        var moderation = new ImageModeration(new ModerationProperties(MODEL.toString(), true, 0.80, 0.97));
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
