package com.booksmith.identity.internal.staff;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedStaff;
import com.booksmith.identity.api.StaffRole;
import com.booksmith.identity.internal.security.SecurityProperties;
import com.booksmith.identity.internal.user.TokenHasher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sessões da equipe, separadas das dos clientes: outro emissor e outra chave (derivada da {@code JWT_SECRET}, então
 * nenhum segredo novo), outras chaves no Redis e outro cookie. Um token de cliente nunca vale aqui, nem o contrário.
 * Token de acesso de 15 minutos; sessão de 12 horas (um dia de trabalho), renovada pelo refresh token do cookie.
 */
@Component
class StaffTokens {

    static final String ISSUER = "booksmith-staff";
    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final Duration REFRESH_GRACE = Duration.ofSeconds(30);
    private static final String SESSION = "staff:session:";
    private static final String SESSIONS = "staff:sessions:";
    private static final String REFRESH = "staff:refresh:";
    private static final String REVOKED = "staff:revoked:";

    private final StringRedisTemplate redis;
    private final Algorithm algorithm;
    private final Clock clock;

    StaffTokens(StringRedisTemplate redis, SecurityProperties properties, Clock clock) {
        this.redis = redis;
        this.clock = clock;
        // Chave própria da equipe: HMAC da chave dos clientes com um rótulo (a dos clientes nunca assina um token daqui)
        this.algorithm = Algorithm.HMAC256(TokenHasher.sha256("booksmith-staff|" + properties.jwtSecret())
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Sessão nova: o token de acesso e o refresh token (vai para o cookie). */
    Issued open(StaffMember member) {
        String sessionId = UUID.randomUUID().toString();
        redis.opsForValue().set(SESSION + sessionId, member.id().toString(), SESSION_TTL);
        redis.opsForSet().add(SESSIONS + member.id(), sessionId);
        redis.expire(SESSIONS + member.id(), SESSION_TTL);
        return new Issued(sign(member, sessionId), newRefresh(member.id(), sessionId));
    }

    Optional<AuthenticatedStaff> verify(String token) {
        try {
            DecodedJWT jwt = JWT.require(algorithm).withIssuer(ISSUER).build().verify(token);
            String sessionId = jwt.getClaim("sid").asString();
            if (Boolean.TRUE.equals(redis.hasKey(REVOKED + jwt.getId()))
                    || !Boolean.TRUE.equals(redis.hasKey(SESSION + sessionId))) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedStaff(UUID.fromString(jwt.getSubject()), jwt.getClaim("email").asString(),
                    jwt.getClaim("name").asString(), StaffRole.valueOf(jwt.getClaim("role").asString()), sessionId));
        } catch (JWTVerificationException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** De quem é o refresh token do cookie (e a sessão dele ainda aberta). */
    Owner owner(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 200) {
            throw expired();
        }
        String value = redis.opsForValue().get(REFRESH + TokenHasher.sha256(refreshToken));
        int bar = value == null ? -1 : value.indexOf('|');
        if (bar < 0 || !Boolean.TRUE.equals(redis.hasKey(SESSION + value.substring(bar + 1)))) {
            throw expired();
        }
        return new Owner(UUID.fromString(value.substring(0, bar)), value.substring(bar + 1));
    }

    /** Token novo e refresh token novo na mesma sessão (o antigo vale só mais 30 segundos). */
    Issued refresh(StaffMember member, Owner owner, String refreshToken) {
        String oldKey = REFRESH + TokenHasher.sha256(refreshToken);
        Long ttl = redis.getExpire(oldKey);
        if (ttl == null || ttl > REFRESH_GRACE.toSeconds()) {
            redis.expire(oldKey, REFRESH_GRACE);
        }
        return new Issued(sign(member, owner.sessionId()), newRefresh(member.id(), owner.sessionId()));
    }

    void close(String refreshToken, String accessToken) {
        if (refreshToken != null && !refreshToken.isBlank() && refreshToken.length() <= 200) {
            String key = REFRESH + TokenHasher.sha256(refreshToken);
            String value = redis.opsForValue().get(key);
            if (value != null && value.indexOf('|') > 0) {
                redis.delete(SESSION + value.substring(value.indexOf('|') + 1));
            }
            redis.delete(key);
        }
        if (accessToken != null) {
            verify(accessToken).ifPresent(staff -> {
                redis.delete(SESSION + staff.sessionId());
                redis.opsForValue().set(REVOKED + JWT.decode(accessToken).getId(), "1", ACCESS_TTL);
            });
        }
    }

    /** Desativado ou reconvidado: todas as sessões da pessoa acabam na hora. */
    void closeAll(UUID memberId) {
        Set<String> ids = redis.opsForSet().members(SESSIONS + memberId);
        if (ids != null) {
            ids.forEach(id -> redis.delete(SESSION + id));
        }
        redis.delete(SESSIONS + memberId);
    }

    private String sign(StaffMember member, String sessionId) {
        return JWT.create()
                .withIssuer(ISSUER)
                .withJWTId(UUID.randomUUID().toString())
                .withSubject(member.id().toString())
                .withClaim("sid", sessionId)
                .withClaim("email", member.email())
                .withClaim("name", member.name())
                .withClaim("role", member.role().name())
                .withExpiresAt(Instant.now(clock).plus(ACCESS_TTL))
                .sign(algorithm);
    }

    private String newRefresh(UUID memberId, String sessionId) {
        String token = TokenHasher.randomUrlToken();
        Long left = redis.getExpire(SESSION + sessionId);
        Duration ttl = left == null || left <= 0 ? SESSION_TTL : Duration.ofSeconds(left);
        redis.opsForValue().set(REFRESH + TokenHasher.sha256(token), memberId + "|" + sessionId, ttl);
        return token;
    }

    private static ApiException expired() {
        return ApiException.unauthorized("Sessão expirada. Entre novamente.");
    }

    record Issued(String accessToken, String refreshToken) {
    }

    record Owner(UUID memberId, String sessionId) {
    }
}
