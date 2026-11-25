/**
 * Estante (§14 da arquitetura, §4.9 do produto): o autor publica o livro (inteiro ou capítulo a capítulo) e qualquer
 * pessoa lê, sem conta. Com conta: seguir autores, biblioteca com o ponto de leitura, curtidas e comentários por
 * capítulo, denúncias. Publicar é separado de salvar: a obra guarda uma cópia em HTML gerada no servidor e só muda
 * quando o autor republica.
 */
@ApplicationModule(
        displayName = "Estante",
        allowedDependencies = {"common", "identity :: api", "manuscript :: api", "export :: api", "notification :: api", "universe :: api"}
)
package com.booksmith.shelf;

import org.springframework.modulith.ApplicationModule;
