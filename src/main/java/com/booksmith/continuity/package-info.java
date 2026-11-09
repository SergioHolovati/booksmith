/**
 * Agente de continuidade (§10 da arquitetura, §4.5 do produto). Primeira parte: verificações
 * determinísticas (atributo fixo e grafia de nomes), que não usam IA nem custam por análise. A extração e o
 * julgamento por LLM entram atrás de uma interface quando o provedor for escolhido (§32).
 */
@ApplicationModule(
        displayName = "Continuidade",
        allowedDependencies = {"common", "identity :: api", "manuscript :: api", "universe :: api"}
)
package com.booksmith.continuity;

import org.springframework.modulith.ApplicationModule;
