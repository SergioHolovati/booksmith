package com.booksmith.app.config.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.booksmith.app.dto.config.UserContextDTO;
import com.booksmith.app.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final String TOKEN_BLACKLIST_PREFIX =
            "auth:blacklist:";

    private static final String SESSION_PREFIX =
            "auth:session:";

    private static final Duration
            MAX_SESSION_DURATION =
            Duration.ofHours(24);

    private final StringRedisTemplate
            redisTemplate;

    @Value(
            "${api.security.token.secret}"
    )
    private String secret;

    @Value(
            "${api.security.token.expiration}"
    )
    private long expirationTime;

    public JwtTokenProvider(
            StringRedisTemplate
                    redisTemplate
    ) {
        this.redisTemplate =
                redisTemplate;
    }

    public String generateToken(
            User user
    ) {

        String sessionId =
                UUID.randomUUID()
                        .toString();

        createSession(
                sessionId,
                user.getId()
        );

        return generateToken(
                user,
                sessionId
        );
    }

    private String generateToken(
            User user,
            String sessionId
    ) {

        Algorithm algorithm =
                Algorithm.HMAC256(
                        secret
                );

        String authorId =
                ObjectUtils.isEmpty(
                        user.getAuthor()
                )
                        ? "-"
                        : user.getAuthor()
                          .getId();

        return JWT.create()
                .withIssuer(
                        "booksmith-app"
                )
                .withJWTId(
                        UUID.randomUUID()
                                .toString()
                )
                .withSubject(
                        user.getId()
                )
                .withClaim(
                        "sessionId",
                        sessionId
                )
                .withClaim(
                        "userName",
                        user.getUsername()
                )
                .withClaim(
                        "firstName",
                        user.getFirstName()
                )
                .withClaim(
                        "lastName",
                        user.getLastName()
                )
                .withClaim(
                        "authorId",
                        authorId
                )
                .withExpiresAt(
                        new Date(
                                System.currentTimeMillis()
                                        + expirationTime
                        )
                )
                .sign(algorithm);
    }

    private boolean isTokenInvalidated(
            String token
    ) {

        return Boolean.TRUE.equals(
                redisTemplate.hasKey(
                        getBlacklistKey(
                                token
                        )
                )
        );
    }

    public boolean validateToken(
            String token
    ) {

        try {

            Algorithm algorithm =
                    Algorithm.HMAC256(
                            secret
                    );

            var decodedJwt =
                    JWT.require(
                                    algorithm
                            )
                            .withIssuer(
                                    "booksmith-app"
                            )
                            .build()
                            .verify(token);

            String sessionId =
                    decodedJwt
                            .getClaim(
                                    "sessionId"
                            )
                            .asString();

            return !isTokenInvalidated(
                    token
            ) && isSessionValid(
                    sessionId
            );

        } catch (
                JWTVerificationException
                        exception
        ) {

            return false;
        }
    }

    public void invalidateToken(
            String token
    ) {

        var decoded =
                JWT.decode(token);

        Date expiresAt =
                decoded
                        .getExpiresAt();

        long ttl =
                expiresAt.getTime()
                        - System
                        .currentTimeMillis();

        if (ttl > 0) {

            redisTemplate
                    .opsForValue()
                    .set(
                            getBlacklistKey(
                                    token
                            ),
                            "true",
                            Duration.ofMillis(
                                    ttl
                            )
                    );
        }
    }

    public String refreshToken(
            String token, User user
    ) {

        var decodedJwt =
                JWT.decode(token);

        String sessionId =
                decodedJwt
                        .getClaim(
                                "sessionId"
                        )
                        .asString();

        // sessão expirou
        if (!isSessionValid(
                sessionId
        )) {

            invalidateToken(
                    token
            );

            throw new RuntimeException(
                    "Session expired. Please login again."
            );
        }

        UserContextDTO
                userContextDTO =
                getUserContextFromToken(
                        token
                );


        String refreshedToken =
                generateToken(
                        user,
                        sessionId
                );

        invalidateToken(
                token
        );

        return refreshedToken;
    }

    public UserContextDTO
    getUserContextFromToken(
            String token
    ) {

        var decodedJwt =
                JWT.decode(token);

        return UserContextDTO
                .builder()
                .userId(
                        decodedJwt
                                .getSubject()
                )
                .userName(
                        decodedJwt
                                .getClaim(
                                        "userName"
                                )
                                .asString()
                )
                .authorId(
                        decodedJwt
                                .getClaim(
                                        "authorId"
                                )
                                .asString()
                )
                .build();
    }

    private void createSession(
            String sessionId,
            String userId
    ) {

        redisTemplate
                .opsForValue()
                .set(
                        getSessionKey(
                                sessionId
                        ),
                        userId,
                        MAX_SESSION_DURATION
                );
    }

    private boolean isSessionValid(
            String sessionId
    ) {

        return Boolean.TRUE.equals(
                redisTemplate.hasKey(
                        getSessionKey(
                                sessionId
                        )
                )
        );
    }

    private String
    getBlacklistKey(
            String token
    ) {

        return TOKEN_BLACKLIST_PREFIX
                + token;
    }

    private String
    getSessionKey(
            String sessionId
    ) {

        return SESSION_PREFIX
                + sessionId;
    }
}