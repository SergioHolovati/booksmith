package com.booksmith.media.internal;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Violência extrema e símbolos de ódio, por comparação da imagem com frases (CLIP ViT-B/32, MIT, ONNX int8: só a
 * parte de imagem roda no servidor). As frases já viraram vetores no notebook ({@code moderation/clip-prompts.json},
 * gerado com o modelo de texto do mesmo CLIP): "seguras" (inclusive batalha de fantasia, guerra histórica, terror e
 * cena de crime, que são de livro), "gore" e "ódio". A nota de cada categoria é a soma das probabilidades das frases
 * dela (softmax com escala 100, como o CLIP).
 * <p>
 * Pré-processamento do CLIP: RGB, o lado menor em 224 (bicúbico), recorte central 224 × 224, normalizado pela média e
 * desvio do CLIP.
 */
final class ClipSafety implements AutoCloseable {

    static final int SIZE = 224;
    private static final float[] MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] STD = {0.26862954f, 0.26130258f, 0.27577711f};
    private static final double SCALE = 100;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String input;
    private final List<String> categories = new ArrayList<>();
    private final List<float[]> prompts = new ArrayList<>();

    ClipSafety(Path model) throws OrtException, IOException {
        this.environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            this.session = environment.createSession(model.toString(), options);
        }
        this.input = session.getInputNames().iterator().next();
        try (InputStream in = ClipSafety.class.getResourceAsStream("/moderation/clip-prompts.json")) {
            if (in == null) {
                throw new IOException("moderation/clip-prompts.json não encontrado");
            }
            JsonNode root = JsonMapper.builder().build().readTree(in);
            for (var entry : root.properties()) {
                for (JsonNode vector : entry.getValue().get("embeddings")) {
                    float[] values = new float[vector.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = (float) vector.get(i).asDouble();
                    }
                    categories.add(entry.getKey());
                    prompts.add(values);
                }
            }
        }
    }

    /** A nota de cada categoria ({@code safe}, {@code gore}, {@code hate}), somando 1. */
    Map<String, Double> scores(BufferedImage image) throws OrtException {
        float[] embedding;
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels(image)),
                new long[] {1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Map.of(input, tensor))) {
            embedding = ((float[][]) result.get(0).getValue())[0];
        }
        return categoryScores(embedding);
    }

    Map<String, Double> categoryScores(float[] embedding) {
        double norm = 0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        double[] logits = new double[prompts.size()];
        double max = Double.NEGATIVE_INFINITY;
        for (int p = 0; p < prompts.size(); p++) {
            double dot = 0;
            float[] prompt = prompts.get(p);
            for (int i = 0; i < prompt.length; i++) {
                dot += prompt[i] * embedding[i];
            }
            logits[p] = SCALE * dot / norm;
            max = Math.max(max, logits[p]);
        }
        double total = 0;
        for (int p = 0; p < logits.length; p++) {
            logits[p] = Math.exp(logits[p] - max);
            total += logits[p];
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int p = 0; p < logits.length; p++) {
            scores.merge(categories.get(p), logits[p] / total, Double::sum);
        }
        return scores;
    }

    /** Lado menor em 224 (bicúbico) e recorte central, canais separados e normalizados como o CLIP. */
    static float[] pixels(BufferedImage source) {
        double scale = (double) SIZE / Math.min(source.getWidth(), source.getHeight());
        int w = Math.max(SIZE, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(SIZE, (int) Math.round(source.getHeight() * scale));
        BufferedImage resized = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, SIZE, SIZE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source, -(w - SIZE) / 2, -(h - SIZE) / 2, w, h, null);
        } finally {
            g.dispose();
        }
        int plane = SIZE * SIZE;
        float[] out = new float[3 * plane];
        int[] rgb = resized.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        for (int i = 0; i < plane; i++) {
            int p = rgb[i];
            out[i] = (((p >> 16) & 0xff) / 255f - MEAN[0]) / STD[0];
            out[plane + i] = (((p >> 8) & 0xff) / 255f - MEAN[1]) / STD[1];
            out[2 * plane + i] = ((p & 0xff) / 255f - MEAN[2]) / STD[2];
        }
        return out;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
