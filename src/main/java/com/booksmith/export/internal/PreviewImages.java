package com.booksmith.export.internal;

import com.booksmith.media.api.ImageStorage.ImageData;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Ilustrações reduzidas para a prévia do PDF: a prévia desenha as páginas em baixa resolução, e montar o PDF com as
 * artes em resolução de impressão (3600 px, vários MB cada) pesava na memória e no tempo à toa.
 */
final class PreviewImages {

    private static final float QUALITY = 0.82f;

    private PreviewImages() {
    }

    /** A imagem com o lado maior até {@code maxSide} px, em JPEG; menor que isso (ou ilegível), fica como está. */
    static ImageData shrink(ImageData image, int maxSide) {
        int[] px = PrintCheck.pixels(image.bytes());
        if (px[0] <= maxSide && px[1] <= maxSide) {
            return image;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image.bytes()));
            if (source == null) {
                return image;
            }
            double scale = (double) maxSide / Math.max(source.getWidth(), source.getHeight());
            int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(source, 0, 0, w, h, null);
            g.dispose();
            return new ImageData(jpeg(small), "image/jpeg", "jpg");
        } catch (IOException | RuntimeException e) {
            return image;
        }
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
