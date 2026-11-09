package com.booksmith.identity.internal.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sessões abertas no Redis.
 * <ul>
 *   <li>{@code auth:session:{sid}} → id do usuário; a existência da chave é o que mantém a sessão viva.</li>
 *   <li>{@code auth:session-info:{sid}} → hash com aparelho e datas, para a tela "Sessões" (§20.6).</li>
 *   <li>{@code auth:user-sessions:{userId}} → conjunto com os ids das sessões do usuário. Ids de sessões
 *   expiradas são removidos na próxima listagem.</li>
 * </ul>
 * O IP não é guardado: o aparelho basta para o usuário reconhecer a sessão, e é um dado pessoal a menos.
 */
@Component
public class SessionRegistry {

    private static final String SESSION_PREFIX = "auth:session:";
    private static final String INFO_PREFIX = "auth:session-info:";
    private static final String USER_PREFIX = "auth:user-sessions:";
    private static final int MAX_USER_AGENT = 400;

    private final StringRedisTemplate redis;
    private final SecurityProperties properties;
    private final Clock clock;

    public SessionRegistry(StringRedisTemplate redis, SecurityProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public String open(UUID userId, String userAgent) {
        String sessionId = UUID.randomUUID().toString();
        String now = Instant.now(clock).toString();
        Duration ttl = properties.sessionTtl();
        redis.opsForValue().set(SESSION_PREFIX + sessionId, userId.toString(), ttl);
        redis.opsForHash().putAll(INFO_PREFIX + sessionId, Map.of(
                "userAgent", truncate(userAgent),
                "createdAt", now,
                "lastActiveAt", now));
        redis.expire(INFO_PREFIX + sessionId, ttl);
        index(userId, sessionId);
        return sessionId;
    }

    public boolean isActive(String sessionId) {
        return sessionId != null && Boolean.TRUE.equals(redis.hasKey(SESSION_PREFIX + sessionId));
    }

    /** Estende a sessão (refresh). Sessões abertas antes do registro de aparelhos entram no índice aqui. */
    public void touch(UUID userId, String sessionId) {
        Duration ttl = properties.sessionTtl();
        redis.expire(SESSION_PREFIX + sessionId, ttl);
        redis.opsForHash().put(INFO_PREFIX + sessionId, "lastActiveAt", Instant.now(clock).toString());
        redis.expire(INFO_PREFIX + sessionId, ttl);
        index(userId, sessionId);
    }

    public List<SessionInfo> list(UUID userId) {
        Set<String> ids = redis.opsForSet().members(USER_PREFIX + userId);
        if (ids == null) {
            return List.of();
        }
        List<SessionInfo> sessions = new ArrayList<>();
        for (String id : ids) {
            Optional<SessionInfo> info = read(userId, id);
            if (info.isPresent()) {
                sessions.add(info.get());
            } else {
                redis.opsForSet().remove(USER_PREFIX + userId, id);
            }
        }
        sessions.sort(Comparator.comparing(SessionInfo::lastActiveAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return sessions;
    }

    /** Encerra uma sessão do usuário. Falso se ela não existe ou é de outra pessoa. */
    public boolean end(UUID userId, String sessionId) {
        String owner = redis.opsForValue().get(SESSION_PREFIX + sessionId);
        if (!userId.toString().equals(owner)) {
            return false;
        }
        remove(userId, sessionId);
        return true;
    }

    /** Encerra todas as sessões do usuário, menos {@code keep} (que pode ser nulo). Devolve quantas encerrou. */
    public int endAll(UUID userId, String keep) {
        Set<String> ids = redis.opsForSet().members(USER_PREFIX + userId);
        if (ids == null) {
            return 0;
        }
        int ended = 0;
        for (String id : ids) {
            if (id.equals(keep)) {
                continue;
            }
            if (isActive(id)) {
                ended++;
            }
            remove(userId, id);
        }
        return ended;
    }

    private Optional<SessionInfo> read(UUID userId, String sessionId) {
        if (!userId.toString().equals(redis.opsForValue().get(SESSION_PREFIX + sessionId))) {
            return Optional.empty();
        }
        Map<Object, Object> hash = redis.opsForHash().entries(INFO_PREFIX + sessionId);
        return Optional.of(new SessionInfo(
                sessionId,
                (String) hash.get("userAgent"),
                instant(hash.get("createdAt")),
                instant(hash.get("lastActiveAt"))));
    }

    private void remove(UUID userId, String sessionId) {
        redis.delete(List.of(SESSION_PREFIX + sessionId, INFO_PREFIX + sessionId));
        redis.opsForSet().remove(USER_PREFIX + userId, sessionId);
    }

    private void index(UUID userId, String sessionId) {
        redis.opsForSet().add(USER_PREFIX + userId, sessionId);
        redis.expire(USER_PREFIX + userId, properties.sessionTtl());
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse(value.toString());
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) {
            return "";
        }
        return userAgent.length() > MAX_USER_AGENT ? userAgent.substring(0, MAX_USER_AGENT) : userAgent;
    }

    public record SessionInfo(String id, String userAgent, Instant createdAt, Instant lastActiveAt) {
    }
}
