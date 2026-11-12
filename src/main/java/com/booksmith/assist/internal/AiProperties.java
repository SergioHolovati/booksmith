package com.booksmith.assist.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração das sugestões de IA.
 *
 * @param provider    {@code anthropic} (API do Claude) ou {@code off}
 * @param dailyLimit  pedidos por pessoa em 24 horas
 * @param concurrency pedidos à IA ao mesmo tempo, somando todas as pessoas (os demais esperam na fila)
 */
@ConfigurationProperties("booksmith.ai")
public record AiProperties(String provider, int dailyLimit, int concurrency, Anthropic anthropic) {

    /** @param apiKey vazio = sem chave: a API sobe com a IA desligada e um aviso no log */
    public record Anthropic(String apiKey, String model) {
    }
}
