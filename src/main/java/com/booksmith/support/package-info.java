/**
 * Suporte ao usuário (§20.6 da arquitetura): pedidos com contexto técnico automático, encaminhados à
 * ferramenta de atendimento pelo {@code HelpdeskGateway}. Acesso ao manuscrito com autorização, backoffice e
 * auditoria vêm depois.
 */
@ApplicationModule(
        displayName = "Suporte",
        allowedDependencies = {"common", "identity :: api"}
)
package com.booksmith.support;

import org.springframework.modulith.ApplicationModule;
