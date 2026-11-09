package com.booksmith.identity.internal.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.internal.user.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tokens JWT curtos + sessão no Redis. O token expira em {@code accessTokenTtl}; a sessão, renovada a cada
 * refresh, dura {@code sessionTtl}. Logout remove a sessão e invalida o token pelo {@code jti}.
 */
@Component
public class JwtTokenProvider {

    private static final String ISSUER = "booksmith";
    private static final String SESSION_CLAIM = "sid";
    private static final String REVOKED_PREFIX = "auth:revoked:";

    private final StringRedisTemplate redis;
    private final SessionRegistry sessions;
    private final SecurityProperties properties;
    private final Algorithm algorithm;

    public JwtTokenProvider(StringRedisTemplate redis, SessionRegistry sessions, SecurityProperties properties) {
        this.redis = redis;
        this.sessions = sessions;
        this.properties = properties;
        this.algorithm = Algorithm.HMAC256(properties.jwtSecret());
    }

    /** Abre uma sessão nova, registrando o aparelho pelo User-Agent, e emite o primeiro token dela. */
    public IssuedToken issue(User user, String userAgent) {
        String sessionId = sessions.open(user.getId(), userAgent);
        return sign(user, sessionId);
    }

    /** Valida o token de uma requisição. Vazio se inválido, expirado, revogado ou com sessão encerrada. */
    public Optional<AuthenticatedUser> authenticate(String token) {
        try {
            DecodedJWT jwt = verifier(0).verify(token);
            if (isRevoked(jwt) || !isSessionActive(jwt)) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(
                    UUID.fromString(jwt.getSubject()),
                    jwt.getClaim("username").asString(),
                    jwt.getClaim("name").asString(),
                    jwt.getClaim(SESSION_CLAIM).asString()));
        } catch (JWTVerificationException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Confere um token para renovação. Aceita token expirado (dentro da janela da sessão), mas exige
     * assinatura válida, sessão ativa e token não revogado.
     */
    public RefreshCandidate verifyForRefresh(String token) {
        try {
            DecodedJWT jwt = verifier(properties.sessionTtl().toSeconds()).verify(token);
            if (isRevoked(jwt) || !isSessionActive(jwt)) {
                throw ApiException.unauthorized("Sessão expirada. Entre novamente.");
            }
            return new RefreshCandidate(UUID.fromString(jwt.getSubject()), jwt);
        } catch (JWTVerificationException | IllegalArgumentException e) {
            throw ApiException.unauthorized("Token inválido.");
        }
    }

    /** Emite um token novo na mesma sessão, estende a sessão e revoga o token anterior. */
    public IssuedToken refresh(RefreshCandidate candidate, User user) {
        String sessionId = candidate.jwt().getClaim(SESSION_CLAIM).asString();
        sessions.touch(user.getId(), sessionId);
        revoke(candidate.jwt());
        return sign(user, sessionId);
    }

    /** Encerra a sessão do token (logout). Tokens inválidos são ignorados. */
    public void logout(String token) {
        try {
            DecodedJWT jwt = verifier(properties.sessionTtl().toSeconds()).verify(token);
            sessions.end(UUID.fromString(jwt.getSubject()), jwt.getClaim(SESSION_CLAIM).asString());
            revoke(jwt);
        } catch (JWTVerificationException | IllegalArgumentException ignored) {
            // Token inválido: não há sessão a encerrar
        }
    }

    private IssuedToken sign(User user, String sessionId) {
        Instant expiresAt = Instant.now().plus(properties.accessTokenTtl());
        String token = JWT.create()
                .withIssuer(ISSUER)
                .withJWTId(UUID.randomUUID().toString())
                .withSubject(user.getId().toString())
                .withClaim(SESSION_CLAIM, sessionId)
                .withClaim("username", user.getUsername())
                .withClaim("name", user.displayName())
                .withExpiresAt(expiresAt)
                .sign(algorithm);
        return new IssuedToken(token, properties.accessTokenTtl());
    }

    private JWTVerifier verifier(long expiredLeewaySeconds) {
        return JWT.require(algorithm)
                .withIssuer(ISSUER)
                .acceptExpiresAt(expiredLeewaySeconds)
                .build();
    }

    private void revoke(DecodedJWT jwt) {
        Duration ttl = Duration.between(Instant.now(), jwt.getExpiresAtAsInstant());
        if (!ttl.isNegative() && !ttl.isZero()) {
            redis.opsForValue().set(REVOKED_PREFIX + jwt.getId(), "1", ttl);
        }
    }

    private boolean isRevoked(DecodedJWT jwt) {
        return Boolean.TRUE.equals(redis.hasKey(REVOKED_PREFIX + jwt.getId()));
    }

    private boolean isSessionActive(DecodedJWT jwt) {
        return sessions.isActive(jwt.getClaim(SESSION_CLAIM).asString());
    }

    public record IssuedToken(String value, Duration expiresIn) {
    }

    public record RefreshCandidate(UUID userId, DecodedJWT jwt) {
    }
}
