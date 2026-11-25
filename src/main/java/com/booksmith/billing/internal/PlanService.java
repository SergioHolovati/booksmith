package com.booksmith.billing.internal;

import com.booksmith.billing.api.Entitlements;
import com.booksmith.billing.api.Plan;
import com.booksmith.billing.api.PlanLimits;
import com.booksmith.common.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * O plano de cada conta: o concedido ({@code billing.subscriptions}) enquanto não vence, senão o padrão. As recusas
 * são 402 com o plano atual, para o app oferecer "Ver planos".
 */
@Slf4j
@Service
public class PlanService implements Entitlements {

    private final JdbcClient jdbc;
    private final BillingProperties properties;
    private final Clock clock;

    PlanService(JdbcClient jdbc, BillingProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** Um plano concedido. {@code expiresAt} null: sem prazo. */
    public record Subscription(UUID userId, Plan plan, String source, boolean founder, Instant expiresAt, String note,
                               UUID grantedBy, Instant grantedAt) {
    }

    @Override
    public Plan plan(UUID userId) {
        return active(userId).map(Subscription::plan).orElse(properties.defaultPlan());
    }

    @Override
    public void requireRoom(UUID userId, long current, int limit, String what) {
        if (limit != PlanLimits.UNLIMITED && current >= limit) {
            Plan plan = plan(userId);
            throw refusal("plan_limit", "No plano %s dá para ter até %d %s. Veja os planos para ter mais."
                    .formatted(plan.label(), limit, what), plan);
        }
    }

    @Override
    public void requireFeature(UUID userId, boolean allowed, String feature) {
        if (!allowed) {
            Plan plan = plan(userId);
            throw refusal("plan_feature", "%s não faz parte do plano %s. Veja os planos para liberar."
                    .formatted(capitalize(feature), plan.label()), plan);
        }
    }

    /** O plano concedido, mesmo vencido (o backoffice mostra). */
    public Optional<Subscription> subscription(UUID userId) {
        return jdbc.sql("""
                        select user_id, plan, source, founder, expires_at, note, granted_by, granted_at
                        from billing.subscriptions where user_id = :id""")
                .param("id", userId)
                .query((rs, row) -> new Subscription(rs.getObject("user_id", UUID.class), Plan.valueOf(rs.getString("plan")),
                        rs.getString("source"), rs.getBoolean("founder"),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("note"), rs.getObject("granted_by", UUID.class),
                        rs.getTimestamp("granted_at").toInstant()))
                .optional();
    }

    /** Concede (ou troca) o plano da conta. */
    public Subscription grant(UUID userId, Plan plan, String source, boolean founder, Instant expiresAt, String note,
                              UUID grantedBy) {
        Instant now = Instant.now(clock);
        jdbc.sql("""
                        insert into billing.subscriptions (user_id, plan, source, founder, expires_at, note, granted_by, granted_at)
                        values (:id, :plan, :source, :founder, :expires, :note, :by, :at)
                        on conflict (user_id) do update set plan = excluded.plan, source = excluded.source,
                            founder = excluded.founder, expires_at = excluded.expires_at, note = excluded.note,
                            granted_by = excluded.granted_by, granted_at = excluded.granted_at""")
                .param("id", userId).param("plan", plan.name()).param("source", source).param("founder", founder)
                .param("expires", expiresAt == null ? null : Timestamp.from(expiresAt))
                .param("note", note).param("by", grantedBy).param("at", Timestamp.from(now))
                .update();
        log.info("Plano {} ({}) concedido a {} por {}", plan, source, userId, grantedBy);
        return subscription(userId).orElseThrow();
    }

    /** Tira o plano concedido: a conta volta ao plano padrão (nada do que ela criou some). */
    public boolean revoke(UUID userId) {
        return jdbc.sql("delete from billing.subscriptions where user_id = :id").param("id", userId).update() == 1;
    }

    public Plan defaultPlan() {
        return properties.defaultPlan();
    }

    public String plansUrl() {
        return properties.plansUrl();
    }

    /** As ofertas exibidas em Meu plano (fundador anual e mensal), com o link de pagamento de cada uma. */
    public java.util.List<Offer> offers() {
        return Offer.all(properties.offers());
    }

    private Optional<Subscription> active(UUID userId) {
        Instant now = Instant.now(clock);
        return subscription(userId).filter(s -> s.expiresAt() == null || s.expiresAt().isAfter(now));
    }

    private ApiException refusal(String code, String message, Plan plan) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, code, message,
                Map.<String, Object>of("plan", plan.name(), "plansUrl", properties.plansUrl() == null ? "" : properties.plansUrl()));
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
