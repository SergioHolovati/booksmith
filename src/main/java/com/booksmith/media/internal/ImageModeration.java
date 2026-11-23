package com.booksmith.media.internal;

import ai.onnxruntime.OrtException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * Confere cada imagem antes de ela ser guardada: livre, para revisão (entra e o admin olha) ou bloqueada (não entra).
 * Sem o modelo, desligada (desenvolvimento); com {@code required}, a API não sobe sem ele (produção).
 */
@Slf4j
@Component
class ImageModeration {

    enum Decision { OK, REVIEW, BLOCK, UNCHECKED }

    record Verdict(Decision decision, double score) {
    }

    /** Até duas conferências ao mesmo tempo: o resto espera (a VPS tem poucos núcleos). */
    private final Semaphore slots = new Semaphore(2);
    private final ModerationProperties properties;
    private final NsfwClassifier classifier;

    ImageModeration(ModerationProperties properties) {
        this.properties = properties;
        this.classifier = load(properties);
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
                Optional<Double> score = classifier.score(bytes);
                if (score.isEmpty()) {
                    return new Verdict(Decision.BLOCK, 1);
                }
                Verdict verdict = new Verdict(decide(score.get(), properties), score.get());
                log.debug("Moderação: {} ({}) em {} ms", verdict.decision(), score.get(),
                        (System.nanoTime() - start) / 1_000_000);
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
    }
}
