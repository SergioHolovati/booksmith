package com.booksmith.identity.internal.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedUser;
import com.booksmith.identity.internal.mfa.MfaService;
import com.booksmith.identity.internal.user.TokenHasher;
import com.booksmith.identity.internal.user.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tokens JWT curtos + sessão no Redis. O token de acesso expira em {@code accessTokenTtl} e fica só na memória do
 * app; a sessão, renovada a cada refresh, dura {@code sessionTtl}. Renovar pede o <b>refresh token</b>: um valor
 * aleatório que viaja só no cookie {@code HttpOnly} (o JavaScript não lê, então uma falha de XSS não leva a sessão),
 * guardado no Redis só como hash e trocado a cada uso. Logout remove a sessão e invalida o token pelo {@code jti}.
 */
@Component
public class JwtTokenProvider {

    private static final String ISSUER = "booksmith";
    private static final String SESSION_CLAIM = "sid";
    /** Pendência: {@code "profile"} na conta nova do Google (sem data de nascimento); {@code "mfa"} sem MFA. */
    static final String PENDING_CLAIM = "pending";
    static final String PENDING_PROFILE = "profile";
    /** MFA obrigatório e a conta ainda sem método: só pode configurar o MFA. */
    static final String PENDING_MFA = "mfa";
    private static final String REVOKED_PREFIX = "auth:revoked:";
    private static final String REFRESH_PREFIX = "auth:refresh:";
    /**
     * Depois de trocado, o refresh token antigo ainda vale por alguns segundos: duas abas que renovam juntas (ou uma
     * resposta perdida no celular) não derrubam a sessão.
     */
    static final Duration REFRESH_GRACE = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final SessionRegistry sessions;
    private final SecurityProperties properties;
    private final MfaService mfaService;
    private final Algorithm algorithm;

    public JwtTokenProvider(StringRedisTemplate redis, SessionRegistry sessions, SecurityProperties properties,
                            MfaService mfaService) {
        this.redis = redis;
        this.sessions = sessions;
        this.properties = properties;
        this.mfaService = mfaService;
        this.algorithm = Algorithm.HMAC256(properties.jwtSecret());
    }

    /** Abre uma sessão nova, registrando o aparelho pelo User-Agent, e emite o primeiro token e o refresh token. */
    public IssuedToken issue(User user, String userAgent) {
        String sessionId = sessions.open(user.getId(), userAgent);
        return sign(user, sessionId, newRefreshToken(user.getId(), sessionId));
    }

    /** Valida o token de uma requisição. Vazio se inválido, expirado, revogado ou com sessão encerrada. */
    public Optional<AuthenticatedUser> authenticate(String token) {
        return verify(token).map(Verified::user);
    }

    /** Como {@link #authenticate}, dizendo também o que está pendente (cadastro do Google ou MFA). */
    public Optional<Verified> verify(String token) {
        try {
            DecodedJWT jwt = verifier(0).verify(token);
            if (isRevoked(jwt) || !isSessionActive(jwt)) {
                return Optional.empty();
            }
            return Optional.of(new Verified(new AuthenticatedUser(
                    UUID.fromString(jwt.getSubject()),
                    jwt.getClaim("username").asString(),
                    jwt.getClaim("name").asString(),
                    jwt.getClaim(SESSION_CLAIM).asString()),
                    jwt.getClaim(PENDING_CLAIM).asString()));
        } catch (JWTVerificationException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Token novo na mesma sessão, com os dados de agora (ex.: depois de completar o cadastro). */
    public IssuedToken reissue(User user, String sessionId) {
        sessions.touch(user.getId(), sessionId);
        return sign(user, sessionId, null);
    }

    /**
     * Confere o refresh token (do cookie): precisa existir no Redis e a sessão dele continuar aberta.
     *
     * @throws ApiException 401 se não existe, venceu, já foi trocado (passada a folga) ou a sessão acabou
     */
    public RefreshCandidate verifyForRefresh(String refreshToken) {
        Owner owner = refreshOwner(refreshToken)
                .orElseThrow(() -> ApiException.unauthorized("Sessão expirada. Entre novamente."));
        return new RefreshCandidate(owner.userId(), owner.sessionId(), refreshToken);
    }

    /**
     * Emite um token de acesso novo na mesma sessão, estende a sessão e troca o refresh token (o antigo vale só mais
     * {@link #REFRESH_GRACE}).
     */
    public IssuedToken refresh(RefreshCandidate candidate, User user) {
        sessions.touch(user.getId(), candidate.sessionId());
        String oldKey = refreshKey(candidate.refreshToken());
        Long ttl = redis.getExpire(oldKey);
        if (ttl == null || ttl > REFRESH_GRACE.toSeconds()) {
            redis.expire(oldKey, REFRESH_GRACE);
        }
        return sign(user, candidate.sessionId(), newRefreshToken(user.getId(), candidate.sessionId()));
    }

    /**
     * Encerra a sessão (logout) pelo refresh token do cookie e/ou pelo token de acesso. Valores inválidos são
     * ignorados.
     */
    public void logout(String refreshToken, String accessToken) {
        refreshOwner(refreshToken).ifPresent(owner -> {
            sessions.end(owner.userId(), owner.sessionId());
            redis.delete(refreshKey(refreshToken));
        });
        if (accessToken == null) {
            return;
        }
        try {
            DecodedJWT jwt = verifier(properties.sessionTtl().toSeconds()).verify(accessToken);
            sessions.end(UUID.fromString(jwt.getSubject()), jwt.getClaim(SESSION_CLAIM).asString());
            revoke(jwt);
        } catch (JWTVerificationException | IllegalArgumentException ignored) {
            // Token inválido: não há sessão a encerrar
        }
    }

    private String newRefreshToken(UUID userId, String sessionId) {
        String token = TokenHasher.randomUrlToken();
        redis.opsForValue().set(refreshKey(token), userId + "|" + sessionId, properties.sessionTtl());
        return token;
    }

    private Optional<Owner> refreshOwner(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 200) {
            return Optional.empty();
        }
        String value = redis.opsForValue().get(refreshKey(refreshToken));
        int bar = value == null ? -1 : value.indexOf('|');
        if (bar < 0) {
            return Optional.empty();
        }
        String sessionId = value.substring(bar + 1);
        if (!sessions.isActive(sessionId)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Owner(UUID.fromString(value.substring(0, bar)), sessionId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String refreshKey(String refreshToken) {
        return REFRESH_PREFIX + TokenHasher.sha256(refreshToken);
    }

    /** O que falta antes de liberar o app: o cadastro (conta nova do Google) e, depois, o MFA obrigatório. */
    private String pending(User user) {
        if (user.getBirthDate() == null) {
            return PENDING_PROFILE;
        }
        return mfaService.setupPending(user.getId()) ? PENDING_MFA : null;
    }

    private IssuedToken sign(User user, String sessionId, String refreshToken) {
        Instant expiresAt = Instant.now().plus(properties.accessTokenTtl());
        String token = JWT.create()
                .withIssuer(ISSUER)
                .withJWTId(UUID.randomUUID().toString())
                .withSubject(user.getId().toString())
                .withClaim(SESSION_CLAIM, sessionId)
                .withClaim("username", user.getUsername())
                .withClaim("name", user.displayName())
                // Conta nova do Google (sem data de nascimento) ou sem MFA: só pode resolver a pendência
                .withClaim(PENDING_CLAIM, pending(user))
                .withExpiresAt(expiresAt)
                .sign(algorithm);
        return new IssuedToken(token, properties.accessTokenTtl(), refreshToken);
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

    /**
     * {@code pending}: {@code "profile"} (completar o cadastro) ou {@code "mfa"} (configurar o MFA) antes de usar o
     * resto da API; null se nada falta.
     */
    public record Verified(AuthenticatedUser user, String pending) {

        public boolean profilePending() {
            return PENDING_PROFILE.equals(pending);
        }

        public boolean mfaPending() {
            return PENDING_MFA.equals(pending);
        }
    }

    /** {@code refreshToken}: só numa sessão nova ou numa renovação (vai para o cookie); null no resto. */
    public record IssuedToken(String value, Duration expiresIn, String refreshToken) {
    }

    public record RefreshCandidate(UUID userId, String sessionId, String refreshToken) {
    }

    private record Owner(UUID userId, String sessionId) {
    }
}
