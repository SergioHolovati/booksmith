package com.booksmith.app.config.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.booksmith.app.dto.config.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final String TOKEN_BLACKLIST_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redisTemplate;

    @Value("${api.security.token.secret}")
    private String secret;

    @Value("${api.security.token.expiration}")
    private long expirationTime;

    public JwtTokenProvider(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String generateToken(String id, String username) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        return JWT.create()
                .withIssuer("booksmith-app")
                .withJWTId(UUID.randomUUID().toString())
                .withSubject(id)
                .withClaim("userName", username)
                .withExpiresAt(new Date(System.currentTimeMillis() + expirationTime))
                .sign(algorithm);
    }

    public boolean validateToken(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            JWT.require(algorithm)
                    .withIssuer("booksmith-app")
                    .build()
                    .verify(token);
            return !isTokenInvalidated(token);
        } catch (JWTVerificationException exception) {
            return false;
        }
    }

    public void invalidateToken(String token) {
        Date expiresAt = JWT.decode(token).getExpiresAt();
        long ttl = expiresAt.getTime() - System.currentTimeMillis();
        if (ttl > 0) {
            redisTemplate.opsForValue().set(getBlacklistKey(token), "true", Duration.ofMillis(ttl));
        }
    }

    public String refreshToken(String token) {
        UserContext userContext = getUserContextFromToken(token);
        String refreshedToken = generateToken(userContext.getUserId(), userContext.getUserName());
        invalidateToken(token);
        return refreshedToken;
    }

    public UserContext getUserContextFromToken(String token) {
        var decodedJwt = JWT.decode(token);
        return UserContext.builder()
                .userId(decodedJwt.getSubject())
                .userName(decodedJwt.getClaim("userName").asString())
                .build();
    }

    private boolean isTokenInvalidated(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(getBlacklistKey(token)));
    }

    private String getBlacklistKey(String token) {
        return TOKEN_BLACKLIST_PREFIX + token;
    }
}
