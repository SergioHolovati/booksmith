package com.booksmith.export.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportQuotaTest {

    private final Map<String, Long> counters = new HashMap<>();
    private ExportQuota quota;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(i -> counters.merge(i.<String>getArgument(0), 1L, Long::sum));
        when(values.decrement(anyString())).thenAnswer(i -> counters.merge(i.<String>getArgument(0), -1L, Long::sum));
        quota = new ExportQuota(redis, Clock.fixed(Instant.parse("2026-11-13T15:00:00Z"), ZoneOffset.UTC),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class), 2, 5);
    }

    @Test
    void theThirdBookPdfOfTheDayIsRefusedAndDoesNotCount() {
        UUID lia = UUID.randomUUID();
        quota.consumeBookPdf(lia);
        quota.consumeBookPdf(lia);

        assertThatThrownBy(() -> quota.consumeBookPdf(lia))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(429);
                    assertThat(e.getCode()).isEqualTo("pdf_daily_limit");
                });
        assertThat(counters.values()).containsExactly(2L);
        // Outra pessoa tem a própria cota; a capa tem limite separado
        assertThatCode(() -> quota.consumeBookPdf(UUID.randomUUID())).doesNotThrowAnyException();
        assertThatCode(() -> quota.consumeCoverPdf(lia)).doesNotThrowAnyException();
    }

    @Test
    void aFailedGenerationGivesTheUseBack() {
        UUID lia = UUID.randomUUID();
        ExportQuota.Use use = quota.consumeBookPdf(lia);
        quota.refund(use);
        quota.refund(null);

        quota.consumeBookPdf(lia);
        assertThatCode(() -> quota.consumeBookPdf(lia)).doesNotThrowAnyException();
    }
}
