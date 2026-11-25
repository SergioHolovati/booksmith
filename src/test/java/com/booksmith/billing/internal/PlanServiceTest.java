package com.booksmith.billing.internal;

import com.booksmith.billing.api.Plan;
import com.booksmith.billing.api.PlanLimits;
import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private final UUID lia = UUID.randomUUID();

    @Test
    void thePlansFollowTheProductPlan() {
        PlanLimits free = Plan.GRATIS.limits();
        assertThat(free.books()).isEqualTo(1);
        assertThat(free.charactersPerUniverse()).isEqualTo(15);
        assertThat(free.aiWindow().toDays()).isEqualTo(7);
        assertThat(free.illustrationsPerWeek()).isZero();
        assertThat(free.epub()).isFalse();
        assertThat(Plan.ESCRITOR.limits().epub()).isTrue();
        assertThat(Plan.ESCRITOR.limits().print()).isFalse();
        assertThat(Plan.AUTOR.limits().print()).isTrue();
        assertThat(Plan.AUTOR.limits().aiUses()).isGreaterThan(Plan.ESCRITOR.limits().aiUses());
    }

    @Test
    void withoutAGrantedPlanTheDefaultApplies() {
        assertThat(service(Plan.AUTOR, Optional.empty()).plan(lia)).isEqualTo(Plan.AUTOR);
        assertThat(service(Plan.GRATIS, Optional.empty()).plan(lia)).isEqualTo(Plan.GRATIS);
    }

    @Test
    void aGrantedPlanAppliesUntilItExpires() {
        var founder = granted(Plan.ESCRITOR, NOW.plusSeconds(3600));
        assertThat(service(Plan.GRATIS, Optional.of(founder)).plan(lia)).isEqualTo(Plan.ESCRITOR);
        var expired = granted(Plan.ESCRITOR, NOW.minusSeconds(1));
        assertThat(service(Plan.GRATIS, Optional.of(expired)).plan(lia)).isEqualTo(Plan.GRATIS);
        var forever = granted(Plan.AUTOR, null);
        assertThat(service(Plan.GRATIS, Optional.of(forever)).plan(lia)).isEqualTo(Plan.AUTOR);
    }

    @Test
    void reachingALimitSaysWhichPlanAndWhereToUpgrade() {
        var free = service(Plan.GRATIS, Optional.empty());
        assertThatCode(() -> free.requireRoom(lia, 0, 1, "livros")).doesNotThrowAnyException();
        assertThatThrownBy(() -> free.requireRoom(lia, 1, 1, "livros"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(402);
                    assertThat(e.getCode()).isEqualTo("plan_limit");
                    assertThat(e.getMessage()).contains("Grátis").contains("1 livros");
                });
        // Sem limite nunca recusa
        assertThatCode(() -> free.requireRoom(lia, 1_000_000, PlanLimits.UNLIMITED, "livros")).doesNotThrowAnyException();
        assertThatThrownBy(() -> free.requireFeature(lia, false, "o ePub"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("plan_feature");
                    assertThat(e.getMessage()).startsWith("O ePub");
                });
        assertThatCode(() -> free.requireFeature(lia, true, "o ePub")).doesNotThrowAnyException();
    }

    @Test
    void onlyOffersWithAPaymentLinkHaveAButton() {
        var offers = service(Plan.AUTOR, Optional.empty()).offers();
        assertThat(offers).extracting(Offer::key)
                .containsExactly("escritor-fundador", "autor-fundador", "escritor-mensal", "autor-mensal");
        assertThat(offers).filteredOn(o -> o.url() != null).extracting(Offer::key)
                .containsExactly("escritor-fundador", "escritor-mensal");
        assertThat(offers.getFirst().founder()).isTrue();
        assertThat(offers.getFirst().price()).isEqualTo("R$ 149");
        // Quem bate num limite vai para Meu plano
        assertThat(service(Plan.GRATIS, Optional.empty()).plansUrl()).isEqualTo("/plano");
    }

    private PlanService.Subscription granted(Plan plan, Instant expiresAt) {
        return new PlanService.Subscription(lia, plan, "founder", true, expiresAt, null, null, NOW.minusSeconds(60));
    }

    @SuppressWarnings("unchecked")
    private PlanService service(Plan defaultPlan, Optional<PlanService.Subscription> subscription) {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString()).param(anyString(), any()).query(any(RowMapper.class)).optional())
                .thenAnswer(invocation -> subscription);
        return new PlanService(jdbc, new BillingProperties(defaultPlan, null,
                new BillingProperties.Offers("https://asaas/escritor-mensal", null, "https://asaas/escritor-fundador", "")),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
