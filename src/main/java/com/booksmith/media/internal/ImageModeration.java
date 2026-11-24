package com.booksmith.media.internal;

import ai.onnxruntime.OrtException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

/**
 * Confere cada imagem antes de ela ser guardada: livre, para revisão (entra e o admin olha) ou bloqueada (não entra).
 * Sem o modelo, desligada (desenvolvimento); com {@code required}, a API não sobe sem ele (produção).
 */
@Slf4j
@Component
class ImageModeration {

    enum Decision { OK, REVIEW, BLOCK, UNCHECKED }

    /** @param reason o que fez a imagem ir para a revisão ou ser recusada: nudez, violência, ódio ou ilegível */
    record Verdict(Decision decision, double score, String reason) {

        Verdict(Decision decision, double score) {
            this(decision, score, null);
        }
    }

    /** Até duas conferências ao mesmo tempo: o resto espera (a VPS tem poucos núcleos). */
    private final Semaphore slots = new Semaphore(2);
    private final ModerationProperties properties;
    private final NsfwClassifier classifier;
    private final ClipSafety clip;

    ImageModeration(ModerationProperties properties) {
        this.properties = properties;
        this.classifier = load(properties);
        this.clip = classifier == null ? null : loadClip(properties);
    }

    private static ClipSafety loadClip(ModerationProperties properties) {
        String model = properties.clipModel();
        if (model == null || model.isBlank() || !Files.isRegularFile(Path.of(model))) {
            if (properties.required() && model != null && !model.isBlank()) {
                throw new IllegalStateException("Modelo de violência e ódio não encontrado: " + model);
            }
            log.warn("Moderação de violência e símbolos de ódio DESLIGADA (sem o modelo: {})", model);
            return null;
        }
        try {
            ClipSafety loaded = new ClipSafety(Path.of(model));
            log.info("Moderação de violência e ódio ligada (revisão a partir de {} e {})", properties.goreAt(),
                    properties.hateAt());
            return loaded;
        } catch (OrtException | java.io.IOException e) {
            throw new IllegalStateException("Não foi possível carregar o modelo de violência e ódio: " + model, e);
        }
    }

    private static NsfwClassifier load(ModerationProperties properties) {
        String model = properties.model();
        if (model == null || model.isBlank() || !Files.isRegularFile(Path.of(model))) {
            if (properties.required()) {
                throw new IllegalStateException("Moderação de imagens obrigatória, mas o modelo não foi encontrado: "
                        + model);
            }
            log.warn("Moderação de imagens DESLIGADA: modelo não configurado ({}). Só para desenvolvimento.", model);
            return null;
        }
        try {
            NsfwClassifier loaded = new NsfwClassifier(Path.of(model));
            log.info("Moderação de imagens ligada (revisão a partir de {}, bloqueio a partir de {})",
                    properties.reviewAt(), properties.blockAt());
            return loaded;
        } catch (OrtException e) {
            throw new IllegalStateException("Não foi possível carregar o modelo de moderação: " + model, e);
        }
    }

    boolean enabled() {
        return classifier != null;
    }

    /**
     * @return o veredito; imagem que o servidor não consegue ler (e por isso não confere) é bloqueada, se a moderação
     *         está ligada
     */
    Verdict check(byte[] bytes) {
        if (classifier == null) {
            return new Verdict(Decision.UNCHECKED, 0);
        }
        long start = System.nanoTime();
        try {
            slots.acquire();
            try {
                java.awt.image.BufferedImage image = NsfwClassifier.decode(bytes);
                if (image == null) {
                    return new Verdict(Decision.BLOCK, 1, "ilegível");
                }
                double nudity = classifier.score(image);
                java.util.Map<String, Double> clipScores = clip == null ? java.util.Map.of() : clip.scores(image);
                Verdict verdict = decide(nudity, clipScores.getOrDefault("gore", 0d),
                        clipScores.getOrDefault("hate", 0d), properties);
                log.debug("Moderação: {} ({}, nudez {}, CLIP {}) em {} ms", verdict.decision(), verdict.reason(), nudity,
                        clipScores, (System.nanoTime() - start) / 1_000_000);
                return verdict;
            } finally {
                slots.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Moderação interrompida", e);
        } catch (OrtException e) {
            throw new IllegalStateException("Falha no classificador de imagens", e);
        }
    }

    /**
     * Nudez decide entre liberar, revisar e recusar; violência extrema e símbolo de ódio só mandam para a revisão
     * (são temas de livro: guerra, romance histórico). Vale o motivo mais forte.
     */
    static Verdict decide(double nudity, double gore, double hate, ModerationProperties properties) {
        Decision byNudity = decide(nudity, properties);
        if (byNudity == Decision.BLOCK) {
            return new Verdict(Decision.BLOCK, nudity, "nudez");
        }
        if (hate >= properties.hateAt()) {
            return new Verdict(Decision.REVIEW, hate, "ódio");
        }
        if (gore >= properties.goreAt()) {
            return new Verdict(Decision.REVIEW, gore, "violência");
        }
        return byNudity == Decision.REVIEW ? new Verdict(Decision.REVIEW, nudity, "nudez")
                : new Verdict(Decision.OK, nudity);
    }

    static Decision decide(double score, ModerationProperties properties) {
        if (score >= properties.blockAt()) {
            return Decision.BLOCK;
        }
        return score >= properties.reviewAt() ? Decision.REVIEW : Decision.OK;
    }

    @PreDestroy
    void close() throws OrtException {
        if (classifier != null) {
            classifier.close();
        }
        if (clip != null) {
            clip.close();
        }
    }
}
