/**
 * Bíblia do Universo (§7.4 e §12.1 da arquitetura): universos e, por enquanto, personagens com ficha e
 * formulário de aparência. Lugares, linha do tempo, regras e fatos confirmados entram depois.
 */
@ApplicationModule(
        displayName = "Universo",
        allowedDependencies = {"common", "identity :: api", "media :: api"}
)
package com.booksmith.universe;

import org.springframework.modulith.ApplicationModule;
