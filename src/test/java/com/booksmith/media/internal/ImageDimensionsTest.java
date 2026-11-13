package com.booksmith.media.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageDimensionsTest {

    private static byte[] image(String format, int width, int height) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    /** Só o cabeçalho de um WebP, suficiente para a leitura das dimensões. */
    private static byte[] webp(String chunk, byte[] data) {
        byte[] b = new byte[40];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        System.arraycopy(chunk.getBytes(StandardCharsets.US_ASCII), 0, b, 12, 4);
        System.arraycopy(data, 0, b, 20, data.length);
        return b;
    }

    @Test
    void readsPngAndJpegFromTheHeader() throws IOException {
        assertThat(ImageDimensions.require(image("png", 320, 200), ImageFormat.PNG)).isEqualTo(new ImageDimensions.Size(320, 200));
        assertThat(ImageDimensions.require(image("jpeg", 64, 48), ImageFormat.JPEG)).isEqualTo(new ImageDimensions.Size(64, 48));
    }

    @Test
    void readsTheThreeKindsOfWebp() {
        // VP8X: largura-1 e altura-1 em 24 bits (1600 × 2560)
        byte[] vp8x = webp("VP8X", new byte[] {0, 0, 0, 0, (byte) 0x3F, 0x06, 0x00, (byte) 0xFF, 0x09, 0x00});
        assertThat(ImageDimensions.read(vp8x, ImageFormat.WEBP)).contains(new ImageDimensions.Size(1600, 2560));
        // "VP8 ": tag do quadro, start code 9D 01 2A, depois 14 bits de largura e de altura (800 × 600)
        byte[] vp8 = webp("VP8 ", new byte[] {0, 0, 0, (byte) 0x9D, 0x01, 0x2A, 0x20, 0x03, 0x58, 0x02});
        assertThat(ImageDimensions.read(vp8, ImageFormat.WEBP)).contains(new ImageDimensions.Size(800, 600));
        // VP8L: assinatura 2F e 14 bits de largura-1 e altura-1 (1 × 1)
        byte[] vp8l = webp("VP8L", new byte[] {0x2F, 0, 0, 0, 0});
        assertThat(ImageDimensions.read(vp8l, ImageFormat.WEBP)).contains(new ImageDimensions.Size(1, 1));
    }

    @Test
    void refusesHugeDeclaredSizesAndUnreadableHeaders() {
        byte[] bomb = webp("VP8X", new byte[] {0, 0, 0, 0, (byte) 0x2F, 0x75, 0x00, (byte) 0x2F, 0x75, 0x00}); // 30.000 × 30.000
        assertThatThrownBy(() -> ImageDimensions.require(bomb, ImageFormat.WEBP))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("image_too_big"));

        byte[] fakePng = Arrays.copyOf(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 64);
        assertThatThrownBy(() -> ImageDimensions.require(fakePng, ImageFormat.PNG))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("image_invalid"));
    }
}
