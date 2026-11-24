package com.booksmith.billing.api;

import java.time.Duration;

/**
 * Os planos e o que cada um libera (§6 do plano de produto). Quem cancela volta ao Grátis e nunca perde o trabalho:
 * os limites só impedem criar mais, nunca apagam nem escondem o que já existe.
 */
public enum Plan {

    GRATIS("Grátis", new PlanLimits(1, 1, 15, 3, Duration.ofDays(7), 0, false, false)),
    ESCRITOR("Escritor", new PlanLimits(PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, 3,
            Duration.ofDays(1), 4, true, false)),
    AUTOR("Autor", new PlanLimits(PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, 10,
            Duration.ofDays(1), 12, true, true));

    private final String label;
    private final PlanLimits limits;

    Plan(String label, PlanLimits limits) {
        this.label = label;
        this.limits = limits;
    }

    public String label() {
        return label;
    }

    public PlanLimits limits() {
        return limits;
    }
}
