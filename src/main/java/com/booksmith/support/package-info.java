/**
 * Suporte ao usuário (§20.6 da arquitetura): pedidos com contexto técnico automático, encaminhados à
 * ferramenta de atendimento pelo {@code HelpdeskGateway}. Backoffice: a revisão das imagens que a moderação separou.
 * Acesso ao manuscrito com autorização e auditoria vêm depois.
 */
@ApplicationModule(
        displayName = "Suporte",
        allowedDependencies = {"common", "identity :: api", "media :: api"}
)
package com.booksmith.support;

import org.springframework.modulith.ApplicationModule;
