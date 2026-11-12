package com.booksmith.identity.internal.security;

import com.booksmith.identity.internal.user.TokenHasher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Código de uso único que o login com Google devolve ao app no lugar do token: vale 60 segundos e uma vez só.
 * Assim, o que passa pela URL (e pode ficar no histórico do navegador) não abre a conta depois.
 */
@Component
public class OneTimeLoginCodes {

    static final Duration TTL = Duration.ofSeconds(60);
    private static final String PREFIX = "auth:login-code:";

    private final StringRedisTemplate redis;

    public OneTimeLoginCodes(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Um código novo para o usuário; no Redis fica só o hash. */
    public String create(UUID userId) {
        String code = TokenHasher.randomUrlToken();
        redis.opsForValue().set(PREFIX + TokenHasher.sha256(code), userId.toString(), TTL);
        return code;
    }

    /** O usuário do código, que deixa de valer na mesma hora. Vazio se não existe, venceu ou já foi usado. */
    public Optional<UUID> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String userId = redis.opsForValue().getAndDelete(PREFIX + TokenHasher.sha256(code));
        return Optional.ofNullable(userId).map(UUID::fromString);
    }
}
