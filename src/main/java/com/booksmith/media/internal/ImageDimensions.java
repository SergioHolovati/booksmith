package com.booksmith.media.internal;

import com.booksmith.common.error.ApiException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

/**
 * Largura e altura lidas só do cabeçalho (sem decodificar a imagem), para recusar antes de guardar: um arquivo
 * pequeno que declara 30.000 × 30.000 pixels ("bomba de descompressão") derrubaria o servidor na primeira vez que
 * fosse redimensionado. Um cabeçalho que não se lê também é recusado.
 */
final class ImageDimensions {

    static final int MAX_SIDE = 8_000;
    static final long MAX_PIXELS = 40_000_000L;

    private ImageDimensions() {
    }

    record Size(int width, int height) {
    }

    /** @throws ApiException 422 {@code image_invalid} ou {@code image_too_big} */
    static Size require(byte[] bytes, ImageFormat format) {
        Size size = read(bytes, format).orElseThrow(() ->
                ApiException.unprocessable("image_invalid", "Não foi possível ler a imagem enviada."));
        if (size.width() <= 0 || size.height() <= 0) {
            throw ApiException.unprocessable("image_invalid", "Não foi possível ler a imagem enviada.");
        }
        if (size.width() > MAX_SIDE || size.height() > MAX_SIDE || (long) size.width() * size.height() > MAX_PIXELS) {
            throw ApiException.unprocessable("image_too_big",
                    "A imagem tem %d × %d pixels. O máximo é %d de lado e %d milhões de pixels."
                            .formatted(size.width(), size.height(), MAX_SIDE, MAX_PIXELS / 1_000_000));
        }
        return size;
    }

    static Optional<Size> read(byte[] bytes, ImageFormat format) {
        return switch (format) {
            case PNG -> imageIo(bytes, "png");
            case JPEG -> imageIo(bytes, "jpeg");
            case WEBP -> webp(bytes);
        };
    }

    private static Optional<Size> imageIo(byte[] bytes, String formatName) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName);
        if (!readers.hasNext()) {
            return Optional.empty();
        }
        ImageReader reader = readers.next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, true, true);
            return Optional.of(new Size(reader.getWidth(0), reader.getHeight(0)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        } finally {
            reader.dispose();
        }
    }

    /** RIFF....WEBP + um chunk: VP8X (estendido), "VP8 " (com perdas) ou VP8L (sem perdas). */
    private static Optional<Size> webp(byte[] b) {
        if (b.length < 30) {
            return Optional.empty();
        }
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8X" -> Optional.of(new Size(1 + le24(b, 24), 1 + le24(b, 27)));
            case "VP8 " -> b[23] == (byte) 0x9D && b[24] == 0x01 && b[25] == 0x2A
                    ? Optional.of(new Size(le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF))
                    : Optional.empty();
            case "VP8L" -> b[20] == 0x2F
                    ? Optional.of(new Size(1 + ((b[21] & 0xFF) | (b[22] & 0x3F) << 8),
                            1 + ((b[22] & 0xFF) >> 6 | (b[23] & 0xFF) << 2 | (b[24] & 0x0F) << 10)))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    private static int le24(byte[] b, int at) {
        return le16(b, at) | (b[at + 2] & 0xFF) << 16;
    }
}
