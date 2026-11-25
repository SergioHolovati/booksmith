package com.booksmith.billing.internal;

import com.booksmith.billing.api.Plan;

import java.util.List;

/**
 * Uma oferta exibida em "Meu plano": o preço e o link de pagamento do Asaas (null = o link ainda não foi configurado,
 * o app não mostra o botão).
 *
 * @param founder preço de fundador: anual, com o preço garantido nas renovações
 */
public record Offer(String key, Plan plan, String price, String period, boolean founder, String url) {

    /** As quatro ofertas exibidas, na ordem da tela (fundador primeiro, que é a que queremos vender agora). */
    public static List<Offer> all(BillingProperties.Offers links) {
        return List.of(
                new Offer("escritor-fundador", Plan.ESCRITOR, "R$ 149", "por ano", true, blank(links.escritorFundador())),
                new Offer("autor-fundador", Plan.AUTOR, "R$ 299", "por ano", true, blank(links.autorFundador())),
                new Offer("escritor-mensal", Plan.ESCRITOR, "R$ 24,90", "por mês", false, blank(links.escritorMensal())),
                new Offer("autor-mensal", Plan.AUTOR, "R$ 49,90", "por mês", false, blank(links.autorMensal())));
    }

    private static String blank(String url) {
        return url == null || url.isBlank() ? null : url.strip();
    }
}
