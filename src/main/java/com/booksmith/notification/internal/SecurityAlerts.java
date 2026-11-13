package com.booksmith.notification.internal;

import com.booksmith.common.alerts.SecurityIncident;
import com.booksmith.notification.api.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * "Alguém percebe o ataque": conta os {@link SecurityIncident} por hora no Redis e, quando um tipo passa do limite
 * da hora, manda um e-mail ao administrador ({@code booksmith.alerts.email}), um por tipo por hora. Sem e-mail
 * configurado, fica só o aviso no log. A conferência roda a cada 5 minutos.
 */
@Slf4j
@Component
public class SecurityAlerts {

    static final Duration KEEP = Duration.ofHours(2);
    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter HOUR_KEY = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter HOUR_TEXT = DateTimeFormatter.ofPattern("dd/MM 'às' HH'h'");

    private final StringRedisTemplate redis;
    private final EmailSender emailSender;
    private final AlertProperties properties;
    private final Clock clock;

    SecurityAlerts(StringRedisTemplate redis, EmailSender emailSender, AlertProperties properties, Clock clock) {
        this.redis = redis;
        this.emailSender = emailSender;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener
    public void on(SecurityIncident incident) {
        String key = countKey(incident.kind(), now());
        redis.opsForValue().increment(key);
        redis.expire(key, KEEP);
    }

    /** A cada 5 minutos: cada tipo que passou do limite nesta hora gera um alerta (uma vez por hora). */
    @Scheduled(fixedDelayString = "${booksmith.alerts.check-interval:PT5M}")
    public void check() {
        ZonedDateTime hour = now();
        for (SecurityIncident.Kind kind : SecurityIncident.Kind.values()) {
            long count = count(kind, hour);
            int threshold = threshold(kind);
            if (threshold > 0 && count >= threshold
                    && Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(sentKey(kind, hour), "1", KEEP))) {
                alert(kind, count, hour);
            }
        }
    }

    long count(SecurityIncident.Kind kind, ZonedDateTime hour) {
        String value = redis.opsForValue().get(countKey(kind, hour));
        return value == null ? 0 : Long.parseLong(value);
    }

    private void alert(SecurityIncident.Kind kind, long count, ZonedDateTime hour) {
        String title = "Alerta de segurança: " + describe(kind);
        String message = "%d ocorrências de \"%s\" na hora que começou em %s (horário de Brasília). Limite: %d por hora. Veja o log da API."
                .formatted(count, describe(kind), HOUR_TEXT.format(hour), threshold(kind));
        log.warn("ALERTA {}: {}", kind, message);
        if (properties.email() != null && !properties.email().isBlank()) {
            emailSender.sendSecurityNotice(properties.email(), title, message);
        }
    }

    private int threshold(SecurityIncident.Kind kind) {
        return switch (kind) {
            case LOGIN_LOCKED -> properties.loginLockedPerHour();
            case RATE_LIMITED -> properties.rateLimitedPerHour();
            case MFA_FAILED -> properties.mfaFailedPerHour();
            case SERVER_ERROR -> properties.serverErrorsPerHour();
            case QUOTA_HIT -> properties.quotaHitsPerHour();
        };
    }

    static String describe(SecurityIncident.Kind kind) {
        return switch (kind) {
            case LOGIN_LOCKED -> "contas bloqueadas por senha errada";
            case RATE_LIMITED -> "requisições barradas pelo limite por IP";
            case MFA_FAILED -> "desafios de MFA encerrados por códigos errados";
            case SERVER_ERROR -> "erros 500 na API";
            case QUOTA_HIT -> "limites diários de PDF atingidos";
        };
    }

    private ZonedDateTime now() {
        return clock.instant().atZone(BRASILIA);
    }

    private static String countKey(SecurityIncident.Kind kind, ZonedDateTime hour) {
        return "alert:count:" + kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + HOUR_KEY.format(hour);
    }

    private static String sentKey(SecurityIncident.Kind kind, ZonedDateTime hour) {
        return "alert:sent:" + kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + HOUR_KEY.format(hour);
    }
}
