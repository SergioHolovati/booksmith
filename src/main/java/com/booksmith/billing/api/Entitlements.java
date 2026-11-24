package com.booksmith.billing.api;

import java.util.UUID;

/**
 * O que o plano de cada conta libera. Os módulos consultam aqui antes de criar algo que tem limite ou de liberar uma
 * parte paga. Recusa: 402 {@code plan_limit} (ou {@code plan_feature}) com o plano atual e o que o libera, para o app
 * mostrar "Ver planos".
 */
public interface Entitlements {

    Plan plan(UUID userId);

    default PlanLimits limits(UUID userId) {
        return plan(userId).limits();
    }

    /**
     * @param current quantos a pessoa já tem
     * @param limit   o limite do plano (ex.: {@code limits(userId).books()})
     * @param what    o que é, no plural, para a mensagem ("livros", "universos", "personagens neste universo")
     */
    void requireRoom(UUID userId, long current, int limit, String what);

    /** @param feature o nome para a mensagem ("o ePub", "o PDF para gráfica") */
    void requireFeature(UUID userId, boolean allowed, String feature);
}
