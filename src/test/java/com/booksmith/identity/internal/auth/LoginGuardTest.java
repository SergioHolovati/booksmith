package com.booksmith.identity.internal.auth;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.user.User;
import com.booksmith.notification.api.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginGuardTest {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Set<String>> sets = new HashMap<>();
    private final EmailSender emailSender = mock(EmailSender.class);
    private LoginGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Redis em memória: contadores e conjuntos
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.opsForSet()).thenReturn(setOps);
        when(ops.get(anyString())).thenAnswer(i -> values.get(i.<String>getArgument(0)));
        when(ops.increment(anyString())).thenAnswer(i -> {
            long next = Long.parseLong(values.getOrDefault(i.<String>getArgument(0), "0")) + 1;
            values.put(i.getArgument(0), String.valueOf(next));
            return next;
        });
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redis.delete(anyString())).thenAnswer(i -> values.remove(i.<String>getArgument(0)) != null);
        when(redis.hasKey(anyString())).thenAnswer(i -> sets.containsKey(i.<String>getArgument(0)));
        when(setOps.add(anyString(), any(String[].class))).thenAnswer(i -> {
            boolean added = sets.computeIfAbsent(i.getArgument(0), k -> new HashSet<>()).add(i.getArgument(1));
            return added ? 1L : 0L;
        });
        guard = new LoginGuard(redis, emailSender, Clock.fixed(Instant.parse("2026-11-12T20:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void fifthWrongPasswordLocksTheAccountAndWarnsTheOwnerOnce() {
        User lia = User.builder().id(UUID.randomUUID()).email("lia@example.com").build();
        for (int i = 0; i < LoginGuard.MAX_FAILURES - 1; i++) {
            guard.checkNotLocked("Lia@Example.com");
            guard.recordFailure("lia@example.com", lia);
        }
        guard.checkNotLocked("lia@example.com");
        guard.recordFailure("LIA@example.com ", lia);

        assertThatThrownBy(() -> guard.checkNotLocked("lia@example.com"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("login_locked"));
        guard.recordFailure("lia@example.com", lia);
        verify(emailSender, times(1)).sendSecurityNotice(eq("lia@example.com"), eq("Login bloqueado por 15 minutos"), anyString());
    }

    @Test
    void unknownEmailLocksTooButNobodyIsEmailed() {
        for (int i = 0; i < LoginGuard.MAX_FAILURES; i++) {
            guard.recordFailure("ninguem@example.com", null);
        }
        assertThatThrownBy(() -> guard.checkNotLocked("ninguem@example.com")).isInstanceOf(ApiException.class);
        verify(emailSender, never()).sendSecurityNotice(anyString(), anyString(), anyString());
    }

    @Test
    void rightPasswordClearsTheCount() {
        for (int i = 0; i < LoginGuard.MAX_FAILURES - 1; i++) {
            guard.recordFailure("lia@example.com", null);
        }
        guard.recordSuccess("lia@example.com");
        guard.recordFailure("lia@example.com", null);
        guard.checkNotLocked("lia@example.com");
    }

    @Test
    void newDeviceIsEmailedButTheFirstOneAndKnownOnesAreNot() {
        User lia = User.builder().id(UUID.randomUUID()).email("lia@example.com").build();
        String chromeWindows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
        String safariIphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";

        guard.noteDevice(lia, chromeWindows);
        guard.noteDevice(lia, chromeWindows);
        verify(emailSender, never()).sendSecurityNotice(anyString(), anyString(), anyString());

        guard.noteDevice(lia, safariIphone);
        verify(emailSender).sendSecurityNotice(eq("lia@example.com"), eq("Novo acesso à sua conta"),
                contains("Safari no iPhone, em 12/11/2026 às 17:30 (horário de Brasília)"));
    }
}
