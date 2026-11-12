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
 * O que as sugestões e a revisão dividem: o limite por pessoa em 24 horas, a fila (o modelo local atende um
 * pedido por vez) e o registro de uso, que guarda só metadados.
 */
@Slf4j
@Component
public class AiUsage {

    /** Quanto um pedido espera na fila antes de desistir. */
    static final Duration QUEUE_WAIT = Duration.ofMinutes(2);

    private final IdeaModel model;
    private final AiProperties properties;
    private final SuggestionLogRepository repository;
    private final Clock clock;
    private final Semaphore gate;

    public AiUsage(IdeaModel model, AiProperties properties, SuggestionLogRepository repository, Clock clock) {
        this.model = model;
        this.properties = properties;
        this.repository = repository;
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
                    "Você usou os %d pedidos à IA de hoje. Amanhã tem mais.".formatted(properties.dailyLimit()));
        }
    }

    public int remaining(UUID ownerId) {
        Instant dayAgo = Instant.now(clock).minus(Duration.ofDays(1));
        long used = repository.countByUserIdAndCreatedAtAfter(ownerId, dayAgo);
        return (int) Math.max(0, properties.dailyLimit() - used);
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
