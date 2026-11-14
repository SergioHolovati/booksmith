package com.booksmith.assist.internal.illustration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Ilustrações com IA.
 *
 * @param provider        {@code flux} (Black Forest Labs), {@code fake} (imagem de teste, sem custo; desenvolvimento
 *                        e testes automáticos) ou {@code off}. Sem chave, {@code flux} fica desligado.
 * @param weeklyLimit     imagens por pessoa nos últimos 7 dias (só as que deram certo contam)
 * @param concurrency     gerações ao mesmo tempo, somando todas as pessoas
 * @param timeout         quanto esperar o fornecedor antes de desistir
 * @param flux            chave, modelo, endereço e o filtro de conteúdo (0 a 7; 0 é o mais rígido)
 */
@ConfigurationProperties("booksmith.illustrations")
public record IllustrationProperties(String provider, int weeklyLimit, int concurrency, Duration timeout, Flux flux) {

    public IllustrationProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(120);
        }
        if (flux == null) {
            flux = new Flux(null, null, null, 2);
        }
    }

    public record Flux(String apiKey, String model, String baseUrl, int safetyTolerance) {

        public Flux {
            model = model == null || model.isBlank() ? "flux-2-pro" : model;
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.bfl.ai" : baseUrl;
        }
    }

    /** O fornecedor efetivo: {@code flux} sem chave vira {@code off}. */
    public String effectiveProvider() {
        if ("flux".equals(provider) && (flux.apiKey() == null || flux.apiKey().isBlank())) {
            return "off";
        }
        return provider == null || provider.isBlank() ? "off" : provider;
    }
}
