package com.booksmith.common.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class ClockConfig {

    /** Relógio injetável: permite testar regras dependentes de data (idade, expiração de tokens). */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
