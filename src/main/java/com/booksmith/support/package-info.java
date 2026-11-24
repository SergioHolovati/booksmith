/**
 * Suporte ao usuário (§20.6 da arquitetura): pedidos com contexto técnico automático, encaminhados à
 * equipe pelo backoffice (caixa de entrada, conversa, notas internas; e-mail à pessoa e à equipe). Backoffice também:
 * a revisão das imagens que a moderação separou.
 * Acesso ao manuscrito com autorização e auditoria vêm depois.
 */
@ApplicationModule(
        displayName = "Suporte",
        allowedDependencies = {"common", "identity :: api", "media :: api", "notification :: api"}
)
package com.booksmith.support;

import org.springframework.modulith.ApplicationModule;
