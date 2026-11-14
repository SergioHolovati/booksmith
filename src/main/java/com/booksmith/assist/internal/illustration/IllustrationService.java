package com.booksmith.assist.internal.illustration;

import com.booksmith.common.error.ApiException;
import com.booksmith.universe.api.Universes;
import com.booksmith.universe.api.Universes.KnownEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ilustrações com IA (Estúdio Visual, §4.8): o retrato de um personagem (a partir da aparência da ficha) ou a cena
 * de um capítulo (com os retratos dos personagens como referência, para o rosto não mudar). A imagem vai para a
 * biblioteca do universo, marcada como IA. Limite por pessoa em 7 dias; só as que deram certo contam (o fornecedor
 * não cobra as recusadas pelo filtro nem as que falham).
 */
@Slf4j
@Service
public class IllustrationService {

    static final String PORTRAIT = "portrait";
    static final String SCENE = "scene";
    static final Duration WINDOW = Duration.ofDays(7);
    static final Duration QUEUE_WAIT = Duration.ofMinutes(2);
    private static final int PORTRAIT_WIDTH = 896;
    private static final int PORTRAIT_HEIGHT = 1152;

    private final ImageModel model;
    private final IllustrationProperties properties;
    private final ImageGenerationRepository generations;
    private final Universes universes;
    private final Clock clock;
    private final Semaphore gate;

    IllustrationService(ImageModel model, IllustrationProperties properties, ImageGenerationRepository generations,
                        Universes universes, Clock clock) {
        this.model = model;
        this.properties = properties;
        this.generations = generations;
        this.universes = universes;
        this.clock = clock;
        this.gate = new Semaphore(Math.max(1, properties.concurrency()), true);
    }

    /**
     * @param kind          {@code portrait} (retrato de {@code characterId}) ou {@code scene} (cena com {@code characterIds})
     * @param format        cena: {@code page}, {@code opening} ou {@code vignette}
     * @param framing       retrato: {@code face}, {@code half} ou {@code full}
     * @param setAsPortrait retrato: a imagem vira o retrato do personagem
     */
    public record Request(String kind, UUID universeId, UUID characterId, List<UUID> characterIds, String scene,
                          String format, String framing, String expression, String art, Boolean color, String palette,
                          String detail, String background, String extra, Boolean setAsPortrait) {
    }

    /** {@code nextAt}: quando volta a haver vaga, se o limite acabou. */
    public record Status(boolean enabled, int weeklyLimit, int remaining, Instant nextAt) {
    }

    public record Result(UUID imageId, String url, String caption, boolean portrait, int remaining) {
    }

    public Status status(UUID ownerId) {
        Instant weekAgo = Instant.now(clock).minus(WINDOW);
        List<ImageGeneration> counted = generations.findAllByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                ownerId, ImageGeneration.READY, weekAgo);
        int remaining = Math.max(0, properties.weeklyLimit() - counted.size());
        Instant oldest = counted.isEmpty() ? null : counted.getFirst().getCreatedAt();
        Instant nextAt = remaining > 0 || counted.isEmpty() ? null
                : (oldest == null ? Instant.now(clock) : oldest).plus(WINDOW);
        return new Status(enabled(), properties.weeklyLimit(), remaining, nextAt);
    }

    /**
     * @throws ApiException 503 {@code illustrations_disabled}; 429 {@code illustration_weekly_limit};
     *                      422 {@code illustration_invalid} ou {@code illustration_moderated}; 502/504 se o gerador falhar
     */
    public Result generate(UUID ownerId, Request request) {
        if (!enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "illustrations_disabled",
                    "As ilustrações com IA estão desligadas neste servidor.");
        }
        if (request == null || request.universeId() == null) {
            throw ApiException.unprocessable("illustration_invalid", "Escolha o universo da ilustração.");
        }
        universes.requireOwned(ownerId, request.universeId());
        Status status = status(ownerId);
        if (status.remaining() <= 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "illustration_weekly_limit",
                    "Você já criou as %d ilustrações com IA desta semana. A próxima vaga abre em 7 dias contados da mais antiga."
                            .formatted(properties.weeklyLimit()),
                    status.nextAt() == null ? Map.of() : Map.of("nextAt", status.nextAt().toString()));
        }
        Map<UUID, KnownEntity> characters = universes.entities(request.universeId()).stream()
                .filter(e -> "character".equals(e.kind()))
                .collect(Collectors.toMap(KnownEntity::id, Function.identity()));
        IllustrationPrompt.Style style = new IllustrationPrompt.Style(request.art(), !Boolean.FALSE.equals(request.color()),
                request.palette(), request.detail(), request.background());
        Plan plan = PORTRAIT.equals(request.kind()) ? portraitPlan(request, style, characters)
                : SCENE.equals(request.kind()) ? scenePlan(request, style, characters)
                : null;
        if (plan == null) {
            throw ApiException.unprocessable("illustration_invalid", "Escolha retrato ou cena.");
        }
        return run(ownerId, request, plan);
    }

    private Plan portraitPlan(Request request, IllustrationPrompt.Style style, Map<UUID, KnownEntity> characters) {
        KnownEntity character = request.characterId() == null ? null : characters.get(request.characterId());
        if (character == null) {
            throw ApiException.unprocessable("illustration_invalid", "Escolha o personagem do retrato.");
        }
        String prompt = IllustrationPrompt.portrait(style, person(character), request.framing(), request.expression(),
                request.extra());
        // O retrato atual entra como referência: o novo continua parecido com ele
        List<String> references = universes.portraitReference(request.universeId(), character.id()).stream().toList();
        UUID portraitFor = Boolean.TRUE.equals(request.setAsPortrait()) ? character.id() : null;
        return new Plan(PORTRAIT, prompt, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, references, List.of(character.id()),
                "Retrato de " + character.name() + " (IA)", portraitFor);
    }

    private Plan scenePlan(Request request, IllustrationPrompt.Style style, Map<UUID, KnownEntity> characters) {
        List<UUID> ids = request.characterIds() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(request.characterIds()));
        if (ids.size() > IllustrationPrompt.MAX_CHARACTERS) {
            throw ApiException.unprocessable("illustration_invalid",
                    "Escolha até %d personagens para a cena.".formatted(IllustrationPrompt.MAX_CHARACTERS));
        }
        List<IllustrationPrompt.Person> people = new ArrayList<>();
        List<String> references = new ArrayList<>();
        for (UUID id : ids) {
            KnownEntity character = characters.get(id);
            if (character == null) {
                throw ApiException.unprocessable("illustration_invalid", "Personagem não encontrado neste universo.");
            }
            people.add(person(character));
            universes.portraitReference(request.universeId(), id).ifPresent(references::add);
        }
        int[] size = IllustrationPrompt.size(request.format());
        String prompt = IllustrationPrompt.scene(style, request.scene(), people, request.extra());
        String scene = request.scene().strip();
        String caption = (scene.length() > 120 ? scene.substring(0, 119) + "…" : scene) + " (IA)";
        return new Plan(SCENE, prompt, size[0], size[1], references, ids, caption, null);
    }

    private Result run(UUID ownerId, Request request, Plan plan) {
        boolean acquired;
        try {
            acquired = gate.tryAcquire(QUEUE_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy().causedBy(e);
        }
        if (!acquired) {
            throw busy();
        }
        long started = System.nanoTime();
        ImageModel.Generated generated;
        try {
            generated = model.generate(plan.prompt(), plan.width(), plan.height(), plan.references());
        } catch (ApiException e) {
            String status = "illustration_moderated".equals(e.getCode()) ? ImageGeneration.MODERATED : ImageGeneration.FAILED;
            record(ownerId, request.universeId(), plan.kind(), status, null, null, started);
            throw e;
        } finally {
            gate.release();
        }
        Universes.GeneratedImage image = universes.addGeneratedImage(ownerId, request.universeId(), generated.bytes(),
                generated.contentType(), plan.caption(), plan.entityIds(), plan.portraitFor());
        record(ownerId, request.universeId(), plan.kind(), ImageGeneration.READY, generated.costCredits(), image.id(),
                started);
        return new Result(image.id(), image.url(), image.caption(), plan.portraitFor() != null,
                status(ownerId).remaining());
    }

    private void record(UUID ownerId, UUID universeId, String kind, String status, Double cost, UUID imageId,
                        long started) {
        int durationMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        generations.save(ImageGeneration.builder()
                .userId(ownerId).universeId(universeId).kind(kind)
                .provider(model.provider()).model(model.model()).status(status)
                .costCredits(cost == null ? null : BigDecimal.valueOf(cost)).imageId(imageId).durationMs(durationMs)
                .build());
        log.info("Ilustração {} {} em {} ms ({}, {} créditos) para userId: {}", kind, status, durationMs, model.model(),
                cost, ownerId);
    }

    private boolean enabled() {
        return !"off".equals(properties.effectiveProvider());
    }

    /** Aparência da ficha em texto ("cor dos olhos: verdes"), sem campos vazios. */
    static IllustrationPrompt.Person person(KnownEntity character) {
        List<String> traits = new ArrayList<>();
        character.appearance().forEach((key, trait) -> {
            String label = trait.label() == null || trait.label().isBlank() ? trait.value() : trait.label();
            if (label != null && !label.isBlank()) {
                traits.add(words(key) + ": " + label.strip());
            }
        });
        return new IllustrationPrompt.Person(character.name(), traits);
    }

    /** "eyeColor" → "eye color". */
    static String words(String key) {
        return key.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }

    private static ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "illustrations_busy",
                "Muita gente gerando ilustrações agora. Tente de novo em instantes; nada foi descontado.");
    }

    private record Plan(String kind, String prompt, int width, int height, List<String> references,
                        List<UUID> entityIds, String caption, UUID portraitFor) {
    }
}
