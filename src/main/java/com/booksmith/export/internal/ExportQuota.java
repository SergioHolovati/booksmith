package com.booksmith.export.internal;

import com.booksmith.common.alerts.SecurityIncident;
import com.booksmith.common.error.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Limite diário de PDFs por pessoa: gerar PDF é a operação mais cara da API, e sem limite uma conta derruba o
 * servidor para todos (ou faz a conta de hospedagem subir). O PDF do livro e o PDF da capa aberta têm limites
 * separados ({@code booksmith.export.pdf-per-day} e {@code cover-pdf-per-day}); ePub e Word não contam. O dia vira à
 * meia-noite de Brasília. Se a geração falhar, o uso é devolvido.
 */
@Component
public class ExportQuota {

    static final String CODE = "pdf_daily_limit";
    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final Duration KEY_TTL = Duration.ofHours(26);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final int bookPdfPerDay;
    private final int coverPdfPerDay;

    ExportQuota(StringRedisTemplate redis, Clock clock, ApplicationEventPublisher events,
                @Value("${booksmith.export.pdf-per-day:2}") int bookPdfPerDay,
                @Value("${booksmith.export.cover-pdf-per-day:5}") int coverPdfPerDay) {
        this.redis = redis;
        this.clock = clock;
        this.events = events;
        this.bookPdfPerDay = bookPdfPerDay;
        this.coverPdfPerDay = coverPdfPerDay;
    }

    /** Um uso do PDF do livro. @throws ApiException 429 {@code pdf_daily_limit} */
    public Use consumeBookPdf(UUID userId) {
        return consume("pdf", bookPdfPerDay, userId, "PDFs do livro");
    }

    /** Um uso do PDF da capa aberta. @throws ApiException 429 {@code pdf_daily_limit} */
    public Use consumeCoverPdf(UUID userId) {
        return consume("cover", coverPdfPerDay, userId, "PDFs da capa");
    }

    /** A geração falhou: o uso não conta. Aceita null (formato que não tem limite). */
    public void refund(Use use) {
        if (use != null) {
            redis.opsForValue().decrement(use.key());
        }
    }

    private Use consume(String kind, int limit, UUID userId, String what) {
        String key = "export:%s:%s:%s".formatted(kind, userId, LocalDate.now(clock.withZone(BRASILIA)));
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, KEY_TTL);
        if (count != null && count > limit) {
            redis.opsForValue().decrement(key);
            events.publishEvent(new SecurityIncident(SecurityIncident.Kind.QUOTA_HIT, kind));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, CODE,
                    "Você já gerou %d %s hoje, o limite diário. Ele volta à meia-noite (horário de Brasília)."
                            .formatted(limit, what),
                    Map.of("limit", limit));
        }
        return new Use(key);
    }

    public record Use(String key) {
    }
}
