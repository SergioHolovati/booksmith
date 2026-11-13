package com.booksmith.identity.internal.terms;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.user.User;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TermsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    private final TermsAcceptanceRepository acceptances = mock(TermsAcceptanceRepository.class);
    private final TermsService terms = new TermsService(acceptances, Clock.fixed(NOW, ZoneOffset.UTC), "2026-11-13");

    @Test
    void creatingAnAccountRequiresTheAcceptance() {
        assertThatThrownBy(() -> terms.requireAccepted(null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("terms_required"));
        assertThatThrownBy(() -> terms.requireAccepted(false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("terms_required"));
        terms.requireAccepted(true);
    }

    @Test
    void acceptingRecordsTheCurrentVersionAndAHistoryRow() {
        User lia = User.builder().id(UUID.randomUUID()).email("lia@example.com").build();
        assertThat(terms.pending(lia)).isTrue();

        terms.accept(lia);

        assertThat(lia.getTermsVersion()).isEqualTo("2026-11-13");
        assertThat(lia.getTermsAcceptedAt()).isEqualTo(NOW);
        assertThat(terms.pending(lia)).isFalse();
        verify(acceptances).save(any(TermsAcceptance.class));
    }

    @Test
    void aNewVersionMakesTheAccountPendingAgain() {
        User lia = User.builder().id(UUID.randomUUID()).termsVersion("2026-01-01").build();

        assertThat(terms.pending(lia)).isTrue();
        assertThat(new TermsService(acceptances, Clock.systemUTC(), "2026-01-01").pending(lia)).isFalse();
    }
}
