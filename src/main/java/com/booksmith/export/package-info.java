/**
 * Exportação do livro (§13 da arquitetura). Fase 1: ePub 3 e PDF de leitura, gerados na hora, dentro da API
 * (um romance leva poucos segundos). PDF de gráfica, templates e perfis de gráfica entram com o worker de
 * exportação (Fase 2), atrás da mesma tela.
 */
@ApplicationModule(
        displayName = "Exportação",
        allowedDependencies = {"common", "identity :: api", "manuscript :: api", "media :: api", "universe :: api"}
)
package com.booksmith.export;

import org.springframework.modulith.ApplicationModule;
