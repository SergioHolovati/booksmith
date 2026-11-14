package com.booksmith.assist.internal.illustration;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Gerador de teste, sem custo: uma imagem lisa no tamanho pedido, com a cor tirada do pedido (o mesmo pedido dá a
 * mesma cor). Para desenvolvimento e testes automáticos; nunca em produção.
 */
class FakeImageModel implements ImageModel {

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String model() {
        return "fake";
    }

    @Override
    public Generated generate(String prompt, int width, int height, List<String> references) {
        int w = Math.max(16, width / 8);
        int h = Math.max(16, height / 8);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(prompt.hashCode() & 0xFFFFFF));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpeg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Generated(out.toByteArray(), "image/jpeg", 0.0);
    }
}
