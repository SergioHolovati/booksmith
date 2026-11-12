/**
 * Sugestões de IA (§4.11 do produto): ideias e perguntas para o universo, os personagens e o enredo. A IA não
 * escreve a história: devolve possibilidades curtas que o autor usa, adapta ou descarta, sempre marcadas como IA
 * e fora do manuscrito. O modelo fica atrás de {@code IdeaModel}: a API do Claude
 * ({@code booksmith.ai.provider=anthropic}). O Ollama local foi testado e removido em 12/11/2026: os modelos que
 * cabem no PC erravam demais na revisão, e num servidor sairiam mais caros que a API.
 */
@ApplicationModule(
        displayName = "Sugestões de IA",
        allowedDependencies = {"common", "identity :: api", "manuscript :: api", "universe :: api"}
)
package com.booksmith.assist;

import org.springframework.modulith.ApplicationModule;
