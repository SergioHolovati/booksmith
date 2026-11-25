/**
 * Monitoramento de uso e de ataques (backoffice): conta as requisições da API por hora (rota, resposta, tempo, IP,
 * conta) e os {@code SecurityIncident}, guarda 48 horas no Redis e mostra ao administrador. Só números e
 * identificadores técnicos: nunca o conteúdo da requisição.
 *
 * <p>Também as métricas de produto ({@code ProductMetrics}): contagens agregadas lidas, só para leitura, das tabelas
 * dos outros módulos (escrita, livros, universo, continuidade, IA e planos).
 */
@ApplicationModule(displayName = "Monitoramento", allowedDependencies = {"common", "identity :: api"})
package com.booksmith.monitoring;

import org.springframework.modulith.ApplicationModule;
