package com.booksmith.billing.internal;

import com.booksmith.billing.api.Plan;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultPlan o plano de quem não tem um concedido: {@code AUTOR} durante o beta (ninguém perde nada do que já
 *                    usa); {@code GRATIS} quando o cadastro abrir ao público
 * @param plansUrl    onde a pessoa vê os planos e assina (o link da oferta de fundador, por enquanto)
 */
@ConfigurationProperties("booksmith.billing")
public record BillingProperties(Plan defaultPlan, String plansUrl) {

    public BillingProperties {
        defaultPlan = defaultPlan == null ? Plan.GRATIS : defaultPlan;
    }
}
