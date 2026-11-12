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
    /** Tíquete para ligar o Google à conta de quem pediu, no perfil: vale 5 minutos e uma vez só. */
    static final Duration LINK_TTL = Duration.ofMinutes(5);
    private static final String LINK_PREFIX = "auth:google-link:";

    private final StringRedisTemplate redis;

    public OneTimeLoginCodes(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Um código novo para o usuário; no Redis fica só o hash. */
    public String create(UUID userId) {
        return store(PREFIX, userId, TTL);
    }

    /** Tíquete de vínculo com o Google para a conta logada. */
    public String createLinkTicket(UUID userId) {
        return store(LINK_PREFIX, userId, LINK_TTL);
    }

    /** A conta do tíquete de vínculo, que deixa de valer na hora. */
    public Optional<UUID> consumeLinkTicket(String ticket) {
        return take(LINK_PREFIX, ticket);
    }

    private String store(String prefix, UUID userId, Duration ttl) {
        String code = TokenHasher.randomUrlToken();
        redis.opsForValue().set(prefix + TokenHasher.sha256(code), userId.toString(), ttl);
        return code;
    }

    /** O usuário do código, que deixa de valer na mesma hora. Vazio se não existe, venceu ou já foi usado. */
    public Optional<UUID> consume(String code) {
        return take(PREFIX, code);
    }

    private Optional<UUID> take(String prefix, String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String userId = redis.opsForValue().getAndDelete(prefix + TokenHasher.sha256(code));
        return Optional.ofNullable(userId).map(UUID::fromString);
    }
}
