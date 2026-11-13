package com.booksmith.identity.internal.auth;

import com.booksmith.common.error.ApiException;
import com.booksmith.common.alerts.SecurityIncident;
import com.booksmith.identity.internal.user.TokenHasher;
import org.springframework.context.ApplicationEventPublisher;
import com.booksmith.identity.internal.user.User;
import com.booksmith.notification.api.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Proteções do login além do limite por IP: tentativas erradas contadas <b>por conta</b> (quem troca de IP não
 * escapa) e aviso por e-mail quando a conta entra de um aparelho novo.
 *
 * <p>A contagem é pelo e-mail digitado, exista ou não a conta, para a resposta não revelar quem tem cadastro.
 */
@Slf4j
@Component
class LoginGuard {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);
    /** Aparelhos conhecidos ficam lembrados por pouco mais de um ano sem uso. */
    static final Duration DEVICE_MEMORY = Duration.ofDays(400);

    private static final String FAILURES_PREFIX = "auth:fail:";
    private static final String DEVICES_PREFIX = "auth:devices:";
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale.of("pt", "BR"));

    private final StringRedisTemplate redis;
    private final EmailSender emailSender;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    LoginGuard(StringRedisTemplate redis, EmailSender emailSender, Clock clock, ApplicationEventPublisher events) {
        this.redis = redis;
        this.emailSender = emailSender;
        this.clock = clock;
        this.events = events;
    }

    /** Antes de conferir a senha: com 5 erros nos últimos 15 minutos, nem confere (429 {@code login_locked}). */
    void checkNotLocked(String email) {
        String count = redis.opsForValue().get(failuresKey(email));
        if (count != null && Integer.parseInt(count) >= MAX_FAILURES) {
            throw locked();
        }
    }

    /**
     * Senha errada: conta mais uma. Na quinta, bloqueia por 15 minutos e avisa o dono da conta (se ela existe).
     *
     * @param user a conta do e-mail digitado, ou null se não existe
     */
    void recordFailure(String email, User user) {
        String key = failuresKey(email);
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, LOCK);
        if (count != null && count == MAX_FAILURES) {
            events.publishEvent(new SecurityIncident(SecurityIncident.Kind.LOGIN_LOCKED, user == null ? "unknown" : "user"));
            log.warn("Login bloqueado por {} tentativas erradas{}", MAX_FAILURES,
                    user == null ? " (conta inexistente)" : " para userId: " + user.getId());
            if (user != null) {
                emailSender.sendSecurityNotice(user.getEmail(), "Login bloqueado por 15 minutos",
                        "Alguém errou a senha da sua conta %d vezes seguidas. Por segurança, bloqueamos novas tentativas por %d minutos."
                                .formatted(MAX_FAILURES, LOCK.toMinutes()));
            }
        }
    }

    /** Login certo: zera os erros. */
    void recordSuccess(String email) {
        redis.delete(failuresKey(email));
    }

    /**
     * Lembra o aparelho (navegador e sistema, pelo User-Agent) e avisa por e-mail quando é um que a conta nunca
     * usou. O primeiro aparelho de todos não gera aviso.
     */
    void noteDevice(User user, String userAgent) {
        String device = DeviceLabel.describe(userAgent);
        String key = DEVICES_PREFIX + user.getId();
        boolean firstEver = !Boolean.TRUE.equals(redis.hasKey(key));
        Long added = redis.opsForSet().add(key, TokenHasher.sha256(device));
        redis.expire(key, DEVICE_MEMORY);
        if (!firstEver && added != null && added > 0) {
            log.info("Novo aparelho para userId: {} ({})", user.getId(), device);
            emailSender.sendSecurityNotice(user.getEmail(), "Novo acesso à sua conta",
                    "Sua conta do Booksmith foi acessada de um aparelho novo: %s, em %s (horário de Brasília)."
                            .formatted(device, WHEN.format(clock.instant().atZone(BRASILIA))));
        }
    }

    private static String failuresKey(String email) {
        return FAILURES_PREFIX + TokenHasher.sha256(email.trim().toLowerCase(Locale.ROOT));
    }

    private static ApiException locked() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "login_locked",
                "Muitas tentativas erradas. Por segurança, o login desta conta está bloqueado por %d minutos."
                        .formatted(LOCK.toMinutes()), Map.of("retryAfterMinutes", LOCK.toMinutes()));
    }
}
