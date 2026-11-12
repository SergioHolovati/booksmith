package com.booksmith.assist.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuração das sugestões de IA.
 *
 * @param provider   {@code ollama} (modelo local), {@code anthropic} (API do Claude) ou {@code off}
 * @param dailyLimit pedidos por pessoa em 24 horas
 * @param concurrency pedidos atendidos ao mesmo tempo (o modelo local só aguenta um de cada vez)
 */
@ConfigurationProperties("booksmith.ai")
public record AiProperties(String provider, int dailyLimit, int concurrency, Ollama ollama, Anthropic anthropic) {

    /**
     * @param keepAlive quanto tempo o modelo fica carregado na memória depois do último pedido
     * @param timeout   o primeiro pedido carrega o modelo e pode passar de um minuto
     */
    public record Ollama(String url, String model, String keepAlive, Duration timeout) {
    }

    /** @param apiKey vazio = provedor não configurado (a API não sobe com {@code provider=anthropic}) */
    public record Anthropic(String apiKey, String model) {
    }
}
