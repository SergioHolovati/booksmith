/**
 * Sugestões de IA (§4.11 do produto): ideias e perguntas para o universo, os personagens e o enredo. A IA não
 * escreve a história: devolve possibilidades curtas que o autor usa, adapta ou descarta, sempre marcadas como IA
 * e fora do manuscrito. O modelo fica atrás de {@code IdeaModel}: Ollama local por enquanto, a API do Claude
 * quando {@code booksmith.ai.provider=anthropic}.
 */
@ApplicationModule(
        displayName = "Sugestões de IA",
        allowedDependencies = {"common", "identity :: api", "manuscript :: api", "universe :: api"}
)
package com.booksmith.assist;

import org.springframework.modulith.ApplicationModule;
