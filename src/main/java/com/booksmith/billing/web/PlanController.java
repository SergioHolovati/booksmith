package com.booksmith.billing.web;

import com.booksmith.billing.api.Plan;
import com.booksmith.billing.api.PlanLimits;
import com.booksmith.billing.internal.PlanService;
import com.booksmith.identity.api.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** "Meu plano": o plano da conta, até quando vale, o que ele libera e a tabela dos planos para comparar. */
@RestController
@RequestMapping("/api/v1/me/plan")
@RequiredArgsConstructor
class PlanController {

    private final PlanService plans;
    private final CurrentUser currentUser;
    private final java.time.Clock clock;

    /** {@code -1} nos limites = sem limite. */
    record LimitsView(int books, int universes, int charactersPerUniverse, int aiUses, long aiWindowHours,
                      int illustrationsPerWeek, boolean epub, boolean print) {

        static LimitsView of(PlanLimits l) {
            return new LimitsView(open(l.books()), open(l.universes()), open(l.charactersPerUniverse()), l.aiUses(),
                    l.aiWindow().toHours(), l.illustrationsPerWeek(), l.epub(), l.print());
        }

        private static int open(int value) {
            return value == PlanLimits.UNLIMITED ? -1 : value;
        }
    }

    record PlanView(String plan, String label, LimitsView limits) {
    }

    /**
     * @param source    courtesy, founder, asaas; null = o plano padrão
     * @param expiresAt null = sem prazo
     */
    record MyPlan(String plan, String label, String source, boolean founder, Instant expiresAt, LimitsView limits,
                  List<PlanView> plans, String plansUrl) {
    }

    @GetMapping
    MyPlan mine() {
        var userId = currentUser.id();
        Plan plan = plans.plan(userId);
        var subscription = plans.subscription(userId)
                .filter(s -> s.plan() == plan && (s.expiresAt() == null || s.expiresAt().isAfter(Instant.now(clock))));
        return new MyPlan(plan.name(), plan.label(), subscription.map(PlanService.Subscription::source).orElse(null),
                subscription.map(PlanService.Subscription::founder).orElse(false),
                subscription.map(PlanService.Subscription::expiresAt).orElse(null), LimitsView.of(plan.limits()),
                Arrays.stream(Plan.values()).map(p -> new PlanView(p.name(), p.label(), LimitsView.of(p.limits()))).toList(),
                plans.plansUrl());
    }
}
