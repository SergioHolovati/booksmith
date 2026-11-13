package com.booksmith.notification.internal;

import com.booksmith.common.alerts.SecurityIncident;
import com.booksmith.notification.api.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityAlertsTest {

    private final Map<String, String> store = new HashMap<>();
    private final EmailSender emailSender = mock(EmailSender.class);
    private SecurityAlerts alerts;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(i -> {
            long next = Long.parseLong(store.getOrDefault(i.<String>getArgument(0), "0")) + 1;
            store.put(i.getArgument(0), Long.toString(next));
            return next;
        });
        when(values.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(i -> store.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        alerts = new SecurityAlerts(redis, emailSender,
                new AlertProperties("admin@example.com", 3, 100, 10, 10, 10),
                Clock.fixed(Instant.parse("2026-11-13T15:10:00Z"), ZoneOffset.UTC));
    }

    @Test
    void emailsTheAdminOnceWhenAKindPassesItsHourlyThreshold() {
        alerts.on(new SecurityIncident(SecurityIncident.Kind.LOGIN_LOCKED, null));
        alerts.on(new SecurityIncident(SecurityIncident.Kind.LOGIN_LOCKED, null));
        alerts.check();
        verify(emailSender, never()).sendSecurityNotice(anyString(), anyString(), anyString());

        alerts.on(new SecurityIncident(SecurityIncident.Kind.LOGIN_LOCKED, null));
        alerts.check();
        alerts.check(); // a mesma hora não gera outro e-mail
        verify(emailSender, times(1)).sendSecurityNotice(eq("admin@example.com"),
                contains("contas bloqueadas"), contains("3 ocorrências"));
    }

    @Test
    void withoutAnEmailItOnlyLogs() {
        SecurityAlerts silent = new SecurityAlerts(mock(StringRedisTemplate.class, org.mockito.Mockito.RETURNS_DEEP_STUBS),
                emailSender, new AlertProperties("", 1, 1, 1, 1, 1), Clock.systemUTC());
        silent.check();
        verify(emailSender, never()).sendSecurityNotice(anyString(), anyString(), anyString());
    }
}
