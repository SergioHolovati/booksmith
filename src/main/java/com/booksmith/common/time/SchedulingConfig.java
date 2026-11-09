package com.booksmith.common.time;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Jobs agendados (ex.: expurgo da lixeira). Com várias instâncias, migrar para um lock distribuído (ShedLock). */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
