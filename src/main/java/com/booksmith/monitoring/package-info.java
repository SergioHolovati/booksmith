/**
 * Monitoramento de uso e de ataques (backoffice): conta as requisições da API por hora (rota, resposta, tempo, IP,
 * conta) e os {@code SecurityIncident}, guarda 48 horas no Redis e mostra ao administrador. Só números e
 * identificadores técnicos: nunca o conteúdo da requisição.
 */
@ApplicationModule(displayName = "Monitoramento", allowedDependencies = {"common", "identity :: api"})
package com.booksmith.monitoring;

import org.springframework.modulith.ApplicationModule;
