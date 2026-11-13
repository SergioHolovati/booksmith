package com.booksmith.identity.internal.security;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.mfa.MfaService;
import com.booksmith.identity.internal.terms.TermsService;
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
    /** Chaves que ganharam validade curta (o refresh token antigo, depois de trocado). */
    private final Map<String, Duration> shortened = new HashMap<>();
    private final MfaService mfaService = mock(MfaService.class);
    private final TermsService termsService = mock(TermsService.class);
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
        when(values.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        when(redis.getExpire(anyString())).thenReturn(Duration.ofDays(30).toSeconds());
        when(redis.expire(anyString(), any(Duration.class))).thenAnswer(i -> {
            shortened.put(i.getArgument(0), i.getArgument(1));
            return true;
        });
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
                "http://localhost:4200", List.of("http://localhost:4200")), mfaService, termsService);
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
    void logoutByTheCookieEndsSession() {
        var token = provider.issue(user, "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36");

        provider.logout(token.refreshToken(), null);

        assertThat(provider.authenticate(token.value())).isEmpty();
        assertThatThrownBy(() -> provider.verifyForRefresh(token.refreshToken())).isInstanceOf(ApiException.class);
    }

    @Test
    void logoutByTheAccessTokenEndsSessionToo() {
        var token = provider.issue(user, null);

        provider.logout(null, token.value());

        assertThat(provider.authenticate(token.value())).isEmpty();
        assertThatThrownBy(() -> provider.verifyForRefresh(token.refreshToken())).isInstanceOf(ApiException.class);
    }

    @Test
    void refreshKeepsSessionAndSwapsTheRefreshToken() {
        var original = provider.issue(user, "Mozilla/5.0 (Linux; Android 14) Chrome/130.0 Mobile Safari/537.36");

        var refreshed = provider.refresh(provider.verifyForRefresh(original.refreshToken()), user);

        assertThat(provider.authenticate(refreshed.value())).isPresent();
        assertThat(provider.authenticate(refreshed.value()).orElseThrow().sessionId())
                .isEqualTo(provider.authenticate(original.value()).orElseThrow().sessionId());
        assertThat(refreshed.refreshToken()).isNotBlank().isNotEqualTo(original.refreshToken());
        // O antigo só vale mais alguns segundos (duas abas renovando juntas)
        assertThat(shortened).containsValue(JwtTokenProvider.REFRESH_GRACE);
        assertThat(provider.verifyForRefresh(refreshed.refreshToken()).userId()).isEqualTo(user.getId());
    }

    @Test
    void theAccessTokenDoesNotRenewTheSession() {
        // Um token de acesso roubado (XSS) não serve para renovar: renovar pede o cookie HttpOnly
        var token = provider.issue(user, null);

        assertThatThrownBy(() -> provider.verifyForRefresh(token.value())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> provider.verifyForRefresh(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void reissueKeepsTheCookie() {
        var token = provider.issue(user, null);

        assertThat(provider.reissue(user, provider.authenticate(token.value()).orElseThrow().sessionId()).refreshToken())
                .isNull();
    }

    @Test
    void tokenStopsWorkingWhenItsSessionIsEndedElsewhere() {
        var token = provider.issue(user, null);
        String sessionId = provider.authenticate(token.value()).orElseThrow().sessionId();

        activeSessions.remove(sessionId); // "Sair de todos os aparelhos" a partir de outra sessão

        assertThat(provider.authenticate(token.value())).isEmpty();
        assertThatThrownBy(() -> provider.verifyForRefresh(token.refreshToken())).isInstanceOf(ApiException.class);
    }

    @Test
    void accountWithoutMfaIsPendingWhenMfaIsRequired() {
        user.setBirthDate(java.time.LocalDate.of(1990, 3, 4));
        when(mfaService.setupPending(user.getId())).thenReturn(true);

        var verified = provider.verify(provider.issue(user, null).value()).orElseThrow();

        assertThat(verified.mfaPending()).isTrue();
        assertThat(verified.profilePending()).isFalse();

        when(mfaService.setupPending(user.getId())).thenReturn(false);
        var done = provider.reissue(user, verified.user().sessionId());
        assertThat(provider.verify(done.value()).orElseThrow().pending()).isNull();
    }

    @Test
    void newTermsComeBeforeTheMfaAndAfterTheProfile() {
        user.setBirthDate(java.time.LocalDate.of(1990, 3, 4));
        when(termsService.pending(user)).thenReturn(true);
        when(mfaService.setupPending(user.getId())).thenReturn(true);

        var verified = provider.verify(provider.issue(user, null).value()).orElseThrow();

        assertThat(verified.termsPending()).isTrue();
        assertThat(verified.mfaPending()).isFalse();
    }

    @Test
    void theGoogleProfileComesBeforeTheMfa() {
        when(mfaService.setupPending(user.getId())).thenReturn(true);

        assertThat(provider.verify(provider.issue(user, null).value()).orElseThrow().profilePending()).isTrue();
    }

    @Test
    void googleAccountWithoutBirthDateIsPendingUntilTheProfileIsComplete() {
        // O usuário do setUp não tem data de nascimento, como a conta criada pelo Google
        var pending = provider.issue(user, null);
        var verified = provider.verify(pending.value()).orElseThrow();
        assertThat(verified.profilePending()).isTrue();

        user.setBirthDate(java.time.LocalDate.of(1990, 3, 4));
        var complete = provider.reissue(user, verified.user().sessionId());
        assertThat(provider.verify(complete.value()).orElseThrow().profilePending()).isFalse();
        assertThat(provider.verify(complete.value()).orElseThrow().user().sessionId()).isEqualTo(verified.user().sessionId());
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
