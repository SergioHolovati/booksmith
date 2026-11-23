package com.booksmith.media.internal;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * O classificador de nudez e conteúdo sexual (AdamCodd/vit-base-nsfw-detector, Apache-2.0, ViT 384 × 384 em ONNX
 * int8): devolve a probabilidade de a imagem ser imprópria, de 0 a 1. Roda no próprio servidor (ONNX Runtime, CPU):
 * nenhuma imagem sai dele. Treinado com fotos e desenhos; rigoroso de propósito (decote e "sensual" contam).
 * <p>
 * Pré-processamento do modelo ({@code preprocessor_config.json}): RGB, 384 × 384 bilinear, (x / 255 − 0,5) / 0,5;
 * saída: logits [sfw, nsfw].
 */
final class NsfwClassifier implements AutoCloseable {

    static final int SIZE = 384;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String input;

    NsfwClassifier(Path model) throws OrtException {
        this.environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // Duas threads: a VPS tem poucos núcleos e o resto da API continua respondendo
            options.setIntraOpNumThreads(2);
            this.session = environment.createSession(model.toString(), options);
        }
        this.input = session.getInputNames().iterator().next();
    }

    /** A probabilidade de ser imprópria; vazio se a imagem não pôde ser lida. */
    Optional<Double> score(byte[] bytes) throws OrtException {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (image == null) {
            return Optional.empty();
        }
        float[] pixels = pixels(image);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels),
                new long[] {1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Map.of(input, tensor))) {
            float[][] logits = (float[][]) result.get(0).getValue();
            return Optional.of(nsfw(logits[0]));
        }
    }

    /** Softmax dos dois logits: a probabilidade da classe 1 (nsfw). */
    static double nsfw(float[] logits) {
        double max = Math.max(logits[0], logits[1]);
        double sfw = Math.exp(logits[0] - max);
        double nsfw = Math.exp(logits[1] - max);
        return nsfw / (sfw + nsfw);
    }

    /** RGB 384 × 384 (canais separados, como o modelo espera), normalizado para −1 a 1. */
    static float[] pixels(BufferedImage source) {
        BufferedImage scaled = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            // Fundo branco: o transparente do PNG vira papel, não preto
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, SIZE, SIZE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, SIZE, SIZE, null);
        } finally {
            g.dispose();
        }
        int plane = SIZE * SIZE;
        float[] out = new float[3 * plane];
        int[] rgb = scaled.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        for (int i = 0; i < plane; i++) {
            int p = rgb[i];
            out[i] = (((p >> 16) & 0xff) / 255f - 0.5f) / 0.5f;
            out[plane + i] = (((p >> 8) & 0xff) / 255f - 0.5f) / 0.5f;
            out[2 * plane + i] = ((p & 0xff) / 255f - 0.5f) / 0.5f;
        }
        return out;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
