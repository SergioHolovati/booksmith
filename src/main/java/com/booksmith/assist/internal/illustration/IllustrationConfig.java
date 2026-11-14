package com.booksmith.assist.internal.illustration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** O gerador efetivo: FLUX com chave; {@code fake} em desenvolvimento e nos testes; desligado sem chave. */
@Slf4j
@Configuration
class IllustrationConfig {

    @Bean
    ImageModel imageModel(IllustrationProperties properties) {
        String provider = properties.effectiveProvider();
        log.info("Ilustrações com IA: {} ({}; {} por semana)", provider,
                "flux".equals(provider) ? properties.flux().model() : "-", properties.weeklyLimit());
        return "flux".equals(provider) ? new FluxClient(properties) : new FakeImageModel();
    }
}
