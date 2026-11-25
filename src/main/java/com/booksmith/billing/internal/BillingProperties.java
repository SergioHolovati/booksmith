package com.booksmith.billing.internal;

import com.booksmith.billing.api.Plan;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultPlan o plano de quem não tem um concedido: {@code AUTOR} durante o beta (ninguém perde nada do que já
 *                    usa); {@code GRATIS} quando o cadastro abrir ao público
 * @param plansUrl    para onde o app manda quem bate num limite ("Ver planos"): a tela Meu plano
 * @param offers      os links de pagamento do Asaas de cada oferta exibida; vazio = a oferta não aparece
 */
@ConfigurationProperties("booksmith.billing")
public record BillingProperties(Plan defaultPlan, String plansUrl, Offers offers) {

    public BillingProperties {
        defaultPlan = defaultPlan == null ? Plan.GRATIS : defaultPlan;
        plansUrl = plansUrl == null || plansUrl.isBlank() ? "/plano" : plansUrl;
        offers = offers == null ? new Offers(null, null, null, null) : offers;
    }

    /** O anual normal (R$ 199 e R$ 399) fica criado no Asaas, mas não é exibido: só os mensais e os de fundador. */
    public record Offers(String escritorMensal, String autorMensal, String escritorFundador, String autorFundador) {
    }
}
