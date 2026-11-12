package com.booksmith.assist.internal;

import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * O que as sugestões, a revisão e as perguntas dividem: o limite por pessoa em 24 horas, a fila e o registro de
 * uso, que guarda só metadados. O limite é de <b>usos</b>, não de chamadas: uma revisão completa do capítulo
 * (várias chamadas) conta um, pela revisão aberta em {@link #startReview}; um pedido de ideias ou uma pergunta
 * conta um.
 */
@Slf4j
@Component
public class AiUsage {

    /** Quanto um pedido espera na fila antes de desistir. */
    static final Duration QUEUE_WAIT = Duration.ofMinutes(2);
    /** Por quanto tempo uma revisão aberta aceita chamadas. */
    static final Duration REVIEW_TTL = Duration.ofHours(2);
    /** O registro de cada chamada da revisão; não conta na cota (conta a revisão aberta). */
    static final String REVIEW_KIND = "review";

    private final IdeaModel model;
    private final AiProperties properties;
    private final SuggestionLogRepository repository;
    private final ReviewRunRepository runs;
    private final Clock clock;
    private final Semaphore gate;

    public AiUsage(IdeaModel model, AiProperties properties, SuggestionLogRepository repository,
                   ReviewRunRepository runs, Clock clock) {
        this.model = model;
        this.properties = properties;
        this.repository = repository;
        this.runs = runs;
        this.clock = clock;
        this.gate = new Semaphore(Math.max(1, properties.concurrency()), true);
    }

    public IdeaModel model() {
        return model;
    }

    public boolean enabled() {
        return !"off".equals(model.provider());
    }

    public int dailyLimit() {
        return properties.dailyLimit();
    }

    /** Desligada → 503 {@code ai_disabled}; sem cota → 429 {@code ai_daily_limit}. */
    public void requireAvailable(UUID ownerId) {
        if (!enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_disabled",
                    "As sugestões de IA estão desligadas neste servidor.");
        }
        if (remaining(ownerId) <= 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ai_daily_limit",
                    ("Você já usou os %d usos de IA das últimas 24 horas (revisões, ideias e perguntas). A "
                            + "formatação e o resto do app continuam livres.").formatted(properties.dailyLimit()));
        }
    }

    public int remaining(UUID ownerId) {
        Instant dayAgo = Instant.now(clock).minus(Duration.ofDays(1));
        long used = repository.countByUserIdAndKindNotAndCreatedAtAfter(ownerId, REVIEW_KIND, dayAgo)
                + runs.countByUserIdAndCreatedAtAfter(ownerId, dayAgo);
        return (int) Math.max(0, properties.dailyLimit() - used);
    }

    /** Abre uma revisão com IA do capítulo: gasta um uso. Desligada → 503; sem cota → 429. */
    public UUID startReview(UUID ownerId, UUID chapterId, int maxCalls) {
        requireAvailable(ownerId);
        return runs.save(ReviewRun.builder().userId(ownerId).chapterId(chapterId).maxCalls(maxCalls).build())
                .getId();
    }

    /**
     * Uma chamada dentro da revisão aberta. Revisão de outra pessoa ou capítulo, vencida ou sem chamadas → 422
     * {@code review_run_invalid} (o app abre outra, gastando outro uso).
     */
    public void useReview(UUID ownerId, UUID runId, UUID chapterId) {
        if (!enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_disabled",
                    "As sugestões de IA estão desligadas neste servidor.");
        }
        Instant after = Instant.now(clock).minus(REVIEW_TTL);
        if (runId == null || runs.use(runId, ownerId, chapterId, after) == 0) {
            throw ApiException.unprocessable("review_run_invalid",
                    "Esta revisão terminou ou expirou. Toque em \"Revisão com IA\" para começar outra.");
        }
    }

    /**
     * Faz o pedido na vez da fila e registra o uso (só metadados) quando dá certo.
     *
     * @param count quantos itens vieram (ideias ou apontamentos), para o registro
     */
    public <T> T run(UUID ownerId, String kind, String field, String mode, Supplier<T> call,
                     java.util.function.ToIntFunction<T> count) {
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
        T result;
        long started = System.nanoTime();
        try {
            result = call.get();
        } finally {
            gate.release();
        }
        int durationMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int items = count.applyAsInt(result);
        repository.save(SuggestionLog.builder()
                .userId(ownerId).kind(kind).field(field).mode(mode)
                .provider(model.provider()).model(model.model())
                .durationMs(durationMs).ideaCount(items)
                .build());
        log.info("IA {} {} {} em {} ms ({}, {} itens)", kind, field, mode, durationMs, model.model(), items);
        return result;
    }

    private static ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_busy",
                "A IA está ocupada com outros pedidos. Tente de novo em um minuto.");
    }
}
