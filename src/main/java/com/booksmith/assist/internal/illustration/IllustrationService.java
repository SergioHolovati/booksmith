package com.booksmith.assist.internal.illustration;

import com.booksmith.billing.api.Entitlements;
import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.universe.api.Universes;
import com.booksmith.universe.api.Universes.KnownEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ilustrações com IA (Estúdio Visual, §4.8): o retrato de um personagem (a partir da aparência da ficha), a cena de
 * um capítulo (com o lugar do universo e os retratos dos personagens como referência) e a arte da capa de um livro
 * concluído (sem letras: o título entra no editor de capa). A imagem vai para a biblioteca do universo, marcada
 * como IA. Limite por pessoa em 7 dias; só as que deram certo contam (o fornecedor não cobra as recusadas pelo
 * filtro nem as que falham). O pedido ao gerador é montado em {@link IllustrationPrompt}, só com dados
 * estruturados: nenhum modelo de texto participa, então não há custo de tokens.
 */
@Slf4j
@Service
public class IllustrationService {

    static final String PORTRAIT = "portrait";
    static final String SCENE = "scene";
    static final String COVER = "cover";
    static final Duration WINDOW = Duration.ofDays(7);
    static final Duration QUEUE_WAIT = Duration.ofMinutes(2);

    private final ImageModel model;
    private final IllustrationProperties properties;
    private final ImageGenerationRepository generations;
    private final Universes universes;
    private final Manuscripts manuscripts;
    private final Clock clock;
    /** O plano de cada conta (null nos testes: vale o limite do servidor). */
    private final Entitlements entitlements;
    private final Semaphore gate;

    IllustrationService(ImageModel model, IllustrationProperties properties, ImageGenerationRepository generations,
                        Universes universes, Manuscripts manuscripts, Clock clock) {
        this(model, properties, generations, universes, manuscripts, clock, null);
    }

    @Autowired
    IllustrationService(ImageModel model, IllustrationProperties properties, ImageGenerationRepository generations,
                        Universes universes, Manuscripts manuscripts, Clock clock, Entitlements entitlements) {
        this.entitlements = entitlements;
        this.model = model;
        this.properties = properties;
        this.generations = generations;
        this.universes = universes;
        this.manuscripts = manuscripts;
        this.clock = clock;
        this.gate = new Semaphore(Math.max(1, properties.concurrency()), true);
    }

    /**
     * @param kind          {@code portrait} (de {@code characterId}), {@code scene} (com {@code characterIds} e
     *                      {@code placeId}) ou {@code cover} (de {@code bookId}, livro concluído)
     * @param format        cena: {@code page}, {@code opening} ou {@code vignette}
     * @param framing       retrato: {@code face}, {@code half} ou {@code full}
     * @param timeOfDay     cena: {@code dawn}, {@code day}, {@code dusk}, {@code night}, {@code storm}, {@code indoor}
     * @param mood          cena e capa: {@code calm}, {@code tense}, {@code joyful}, {@code melancholic},
     *                      {@code mysterious}, {@code epic}, {@code romantic}, {@code eerie}
     * @param shot          cena: {@code wide}, {@code medium}, {@code close}, {@code low}, {@code high}
     * @param subject       capa: o que ela mostra
     * @param setAsPortrait retrato: a imagem vira o retrato do personagem
     */
    public record Request(String kind, UUID universeId, UUID characterId, List<UUID> characterIds, String scene,
                          String format, String framing, String expression, String art, Boolean color, String palette,
                          String detail, String background, String extra, Boolean setAsPortrait, UUID placeId,
                          String timeOfDay, String mood, String shot, UUID bookId, String subject) {
    }

    /** {@code nextAt}: quando volta a haver vaga, se o limite acabou. */
    public record Status(boolean enabled, int weeklyLimit, int remaining, Instant nextAt) {

        int limit() {
            return weeklyLimit;
        }
    }

    public record Result(UUID imageId, String url, String caption, boolean portrait, int remaining) {
    }

    public Status status(UUID ownerId) {
        Instant weekAgo = Instant.now(clock).minus(WINDOW);
        List<ImageGeneration> counted = generations.findAllByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                ownerId, ImageGeneration.READY, weekAgo);
        int limit = weeklyLimit(ownerId);
        int remaining = Math.max(0, limit - counted.size());
        Instant oldest = counted.isEmpty() ? null : counted.getFirst().getCreatedAt();
        Instant nextAt = remaining > 0 || counted.isEmpty() ? null
                : (oldest == null ? Instant.now(clock) : oldest).plus(WINDOW);
        return new Status(enabled(), limit, remaining, nextAt);
    }

    /** Ilustrações por semana, pelo plano da conta; o {@code weekly-limit} do servidor é só o teto. */
    int weeklyLimit(UUID ownerId) {
        return entitlements == null ? properties.weeklyLimit()
                : Math.min(entitlements.limits(ownerId).illustrationsPerWeek(), properties.weeklyLimit());
    }

    /**
     * @throws ApiException 503 {@code illustrations_disabled}; 429 {@code illustration_weekly_limit};
     *                      422 {@code illustration_invalid}, {@code book_not_complete} ou {@code illustration_moderated};
     *                      502/504 se o gerador falhar
     */
    public Result generate(UUID ownerId, Request request) {
        if (!enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "illustrations_disabled",
                    "As ilustrações com IA estão desligadas neste servidor.");
        }
        if (request == null || request.kind() == null) {
            throw ApiException.unprocessable("illustration_invalid", "Escolha retrato, cena ou capa.");
        }
        Manuscripts.BookBrief book = COVER.equals(request.kind()) ? book(ownerId, request.bookId()) : null;
        UUID universeId = book != null ? book.universeId() : request.universeId();
        if (universeId == null) {
            throw ApiException.unprocessable("illustration_invalid", "Escolha o universo da ilustração.");
        }
        universes.requireOwned(ownerId, universeId);
        Map<UUID, KnownEntity> characters = universes.entities(universeId).stream()
                .filter(e -> "character".equals(e.kind()))
                .collect(Collectors.toMap(KnownEntity::id, Function.identity()));
        IllustrationPrompt.Style style = new IllustrationPrompt.Style(request.art(), !Boolean.FALSE.equals(request.color()),
                request.palette(), request.detail(), request.background());
        Plan plan = switch (request.kind()) {
            case PORTRAIT -> portraitPlan(request, universeId, style, characters);
            case SCENE -> scenePlan(request, universeId, style, characters);
            case COVER -> coverPlan(request, book, style, characters);
            default -> throw ApiException.unprocessable("illustration_invalid", "Escolha retrato, cena ou capa.");
        };
        // O formulário é conferido antes da cota: um pedido inválido diz o que está errado, não "limite da semana"
        Status status = status(ownerId);
        if (status.limit() == 0) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "plan_feature",
                    "As ilustrações com IA fazem parte dos planos pagos. Veja os planos para liberar.", Map.of());
        }
        if (status.remaining() <= 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "illustration_weekly_limit",
                    "Você já criou as %d ilustrações com IA desta semana. A próxima vaga abre em 7 dias contados da mais antiga."
                            .formatted(status.limit()),
                    status.nextAt() == null ? Map.of() : Map.of("nextAt", status.nextAt().toString()));
        }
        return run(ownerId, universeId, plan);
    }

    private Manuscripts.BookBrief book(UUID ownerId, UUID bookId) {
        if (bookId == null) {
            throw ApiException.unprocessable("illustration_invalid", "Escolha o livro da capa.");
        }
        Manuscripts.BookBrief book = manuscripts.bookBrief(ownerId, bookId)
                .orElseThrow(() -> ApiException.notFound("book_not_found", "Livro não encontrado."));
        if (!book.complete()) {
            throw ApiException.unprocessable("book_not_complete",
                    "A capa com IA é para o livro concluído: marque o livro como \"Concluído\" na tela do livro e volte aqui.");
        }
        if (book.universeId() == null) {
            throw ApiException.unprocessable("illustration_invalid", "O livro precisa de um universo para guardar a capa.");
        }
        return book;
    }

    private Plan portraitPlan(Request request, UUID universeId, IllustrationPrompt.Style style,
                              Map<UUID, KnownEntity> characters) {
        KnownEntity character = request.characterId() == null ? null : characters.get(request.characterId());
        if (character == null) {
            throw ApiException.unprocessable("illustration_invalid", "Escolha o personagem do retrato.");
        }
        String prompt = IllustrationPrompt.portrait(style, person(character), request.framing(), request.expression(),
                request.extra());
        // O retrato atual entra como referência: o novo continua parecido com ele
        List<String> references = universes.portraitReference(universeId, character.id()).stream().toList();
        UUID portraitFor = Boolean.TRUE.equals(request.setAsPortrait()) ? character.id() : null;
        int[] size = IllustrationPrompt.PORTRAIT_SIZE;
        return new Plan(PORTRAIT, prompt, size[0], size[1], references, List.of(character.id()),
                "Retrato de " + character.name() + " (IA)", portraitFor);
    }

    private Plan scenePlan(Request request, UUID universeId, IllustrationPrompt.Style style,
                           Map<UUID, KnownEntity> characters) {
        Cast cast = cast(request.characterIds(), universeId, characters);
        IllustrationPrompt.Place place = request.placeId() == null ? null
                : universes.placeBrief(universeId, request.placeId())
                        .map(p -> new IllustrationPrompt.Place(p.name(), p.type(), p.description()))
                        .orElseThrow(() -> ApiException.unprocessable("illustration_invalid", "Lugar não encontrado neste universo."));
        int[] size = IllustrationPrompt.size(request.format());
        String prompt = IllustrationPrompt.scene(style, request.scene(), place, cast.people(), request.timeOfDay(),
                request.mood(), request.shot(), request.extra());
        String scene = request.scene().strip();
        String caption = (scene.length() > 120 ? scene.substring(0, 119) + "…" : scene) + " (IA)";
        List<UUID> entityIds = new ArrayList<>(cast.ids());
        if (request.placeId() != null) {
            entityIds.add(request.placeId());
        }
        return new Plan(SCENE, prompt, size[0], size[1], cast.references(), entityIds, caption, null);
    }

    private Plan coverPlan(Request request, Manuscripts.BookBrief book, IllustrationPrompt.Style style,
                           Map<UUID, KnownEntity> characters) {
        Cast cast = cast(request.characterIds(), book.universeId(), characters);
        String prompt = IllustrationPrompt.cover(style, book.title(), book.fanfic(), request.subject(), cast.people(),
                request.mood(), request.extra());
        int[] size = IllustrationPrompt.COVER_SIZE;
        return new Plan(COVER, prompt, size[0], size[1], cast.references(), cast.ids(),
                "Arte da capa de " + book.title() + " (IA)", null);
    }

    /** Os personagens escolhidos (até 4): descrição da ficha e o retrato de quem tem, como referência. */
    private Cast cast(List<UUID> requested, UUID universeId, Map<UUID, KnownEntity> characters) {
        List<UUID> ids = requested == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(requested));
        if (ids.size() > IllustrationPrompt.MAX_CHARACTERS) {
            throw ApiException.unprocessable("illustration_invalid",
                    "Escolha até %d personagens.".formatted(IllustrationPrompt.MAX_CHARACTERS));
        }
        List<IllustrationPrompt.Person> people = new ArrayList<>();
        List<String> references = new ArrayList<>();
        for (UUID id : ids) {
            KnownEntity character = characters.get(id);
            if (character == null) {
                throw ApiException.unprocessable("illustration_invalid", "Personagem não encontrado neste universo.");
            }
            people.add(person(character));
            universes.portraitReference(universeId, id).ifPresent(references::add);
        }
        return new Cast(ids, people, references);
    }

    private Result run(UUID ownerId, UUID universeId, Plan plan) {
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
            record(ownerId, universeId, plan.kind(), status, null, null, started);
            throw e;
        } finally {
            gate.release();
        }
        Universes.GeneratedImage image = universes.addGeneratedImage(ownerId, universeId, generated.bytes(),
                generated.contentType(), plan.caption(), plan.entityIds(), plan.portraitFor());
        record(ownerId, universeId, plan.kind(), ImageGeneration.READY, generated.costCredits(), image.id(), started);
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

    /** A aparência da ficha, campo → como a ficha mostra ("Verdes"); o prompt filtra e ordena. */
    static IllustrationPrompt.Person person(KnownEntity character) {
        Map<String, String> traits = new LinkedHashMap<>();
        character.appearance().forEach((key, trait) -> {
            String label = trait.label() == null || trait.label().isBlank() ? trait.value() : trait.label();
            if (label != null && !label.isBlank()) {
                traits.put(key, label.strip());
            }
        });
        return new IllustrationPrompt.Person(character.name(), traits);
    }

    private static ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "illustrations_busy",
                "Muita gente gerando ilustrações agora. Tente de novo em instantes; nada foi descontado.");
    }

    private record Plan(String kind, String prompt, int width, int height, List<String> references,
                        List<UUID> entityIds, String caption, UUID portraitFor) {
    }

    private record Cast(List<UUID> ids, List<IllustrationPrompt.Person> people, List<String> references) {
    }
}
