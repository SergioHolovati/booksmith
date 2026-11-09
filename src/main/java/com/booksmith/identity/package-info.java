/**
 * Usuários, autenticação (e-mail/senha e Google), sessões e perfil público de autor.
 * Outros módulos só enxergam {@code identity::api} (usuário autenticado atual).
 */
@ApplicationModule(
        displayName = "Identidade",
        allowedDependencies = {"common", "media :: api", "notification :: api"}
)
package com.booksmith.identity;

import org.springframework.modulith.ApplicationModule;
