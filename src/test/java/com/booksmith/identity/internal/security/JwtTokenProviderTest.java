package com.booksmith.identity.internal.security;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtTokenProviderTest {

    private final Map<String, String> store = new HashMap<>();
    private final Set<String> activeSessions = new HashSet<>();
    private JwtTokenProvider provider;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Redis em memória, suficiente para o comportamento de sessão e revogação
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.hasKey(anyString())).thenAnswer(i -> store.containsKey(i.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(i -> store.remove(i.<String>getArgument(0)) != null);

        SessionRegistry sessions = mock(SessionRegistry.class);
        when(sessions.open(any(UUID.class), any())).thenAnswer(i -> {
            String id = UUID.randomUUID().toString();
            activeSessions.add(id);
            return id;
        });
        when(sessions.isActive(any())).thenAnswer(i -> activeSessions.contains(i.<String>getArgument(0)));
        when(sessions.end(any(UUID.class), anyString())).thenAnswer(i -> activeSessions.remove(i.<String>getArgument(1)));

        provider = new JwtTokenProvider(redis, sessions, new SecurityProperties(
                "test-secret-with-at-least-32-characters!!", Duration.ofHours(1), Duration.ofDays(30),
                "http://localhost:4200", List.of("http://localhost:4200")));
        user = User.builder().id(UUID.randomUUID()).username("lia").firstName("Lia").build();
    }

    @Test
    void issuedTokenAuthenticatesUser() {
        var token = provider.issue(user, "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36");

        var authenticated = provider.authenticate(token.value());

        assertThat(authenticated).isPresent();
        assertThat(authenticated.get().id()).isEqualTo(user.getId());
        assertThat(authenticated.get().displayName()).isEqualTo("Lia");
        assertThat(authenticated.get().sessionId()).isIn(activeSessions);
    }

    @Test
    void logoutEndsSession() {
        var token = provider.issue(user, "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36");

        provider.logout(token.value());

        assertThat(provider.authenticate(token.value())).isEmpty();
        assertThatThrownBy(() -> provider.verifyForRefresh(token.value())).isInstanceOf(ApiException.class);
    }

    @Test
    void refreshRevokesPreviousTokenAndKeepsSession() {
        var original = provider.issue(user, "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36");

        var refreshed = provider.refresh(provider.verifyForRefresh(original.value()), user);

        assertThat(provider.authenticate(original.value())).isEmpty();
        assertThat(provider.authenticate(refreshed.value())).isPresent();
    }

    @Test
    void tokenStopsWorkingWhenItsSessionIsEndedElsewhere() {
        var token = provider.issue(user, null);
        String sessionId = provider.authenticate(token.value()).orElseThrow().sessionId();

        activeSessions.remove(sessionId); // "Sair de todos os aparelhos" a partir de outra sessão

        assertThat(provider.authenticate(token.value())).isEmpty();
        assertThatThrownBy(() -> provider.verifyForRefresh(token.value())).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsTamperedToken() {
        String token = provider.issue(user, "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36").value();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThat(provider.authenticate(tampered)).isEmpty();
        assertThat(provider.authenticate("not-a-jwt")).isEmpty();
    }

    @Test
    void requiresStrongSecret() {
        assertThatThrownBy(() -> new SecurityProperties("short", Duration.ofHours(1), Duration.ofDays(1),
                "http://localhost", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
