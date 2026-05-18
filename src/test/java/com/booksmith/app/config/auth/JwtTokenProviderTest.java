package com.booksmith.app.config.auth;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtTokenProviderTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(redisTemplate);

    JwtTokenProviderTest() {
        ReflectionTestUtils.setField(tokenProvider, "secret", "test-secret");
        ReflectionTestUtils.setField(tokenProvider, "expirationTime", 60000L);
    }

    @Test
    void generateTokenShouldCreateValidTokenWithUserContext() {
        String token = tokenProvider.generateToken("user-id", "john");
        when(redisTemplate.hasKey("auth:blacklist:" + token)).thenReturn(false);

        assertThat(tokenProvider.validateToken(token)).isTrue();
        assertThat(tokenProvider.getUserContextFromToken(token).getUserId()).isEqualTo("user-id");
        assertThat(tokenProvider.getUserContextFromToken(token).getUserName()).isEqualTo("john");
    }

    @Test
    void validateTokenShouldReturnFalseWhenTokenIsBlacklisted() {
        String token = tokenProvider.generateToken("user-id", "john");
        when(redisTemplate.hasKey("auth:blacklist:" + token)).thenReturn(true);

        assertThat(tokenProvider.validateToken(token)).isFalse();
    }

    @Test
    void validateTokenShouldReturnFalseWhenTokenIsInvalid() {
        assertThat(tokenProvider.validateToken("invalid-token")).isFalse();
    }

    @Test
    void invalidateTokenShouldStoreTokenUntilExpiration() {
        String token = tokenProvider.generateToken("user-id", "john");
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        tokenProvider.invalidateToken(token);

        verify(valueOperations).set(eq("auth:blacklist:" + token), eq("true"), any(Duration.class));
    }

    @Test
    void refreshTokenShouldGenerateNewTokenAndInvalidateOldOne() {
        String token = tokenProvider.generateToken("user-id", "john");
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String refreshedToken = tokenProvider.refreshToken(token);

        assertThat(refreshedToken).isNotEqualTo(token);
        assertThat(tokenProvider.getUserContextFromToken(refreshedToken).getUserId()).isEqualTo("user-id");
        verify(valueOperations).set(eq("auth:blacklist:" + token), eq("true"), any(Duration.class));
    }
}
