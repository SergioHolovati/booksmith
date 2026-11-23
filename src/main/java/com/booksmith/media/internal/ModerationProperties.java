package com.booksmith.media.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A moderação das imagens enviadas (o classificador roda no servidor).
 *
 * @param model    caminho do modelo ONNX; vazio = moderação desligada (só em desenvolvimento)
 * @param required sem o modelo, a API não sobe (produção): nenhuma imagem entra sem ser conferida
 * @param reviewAt a partir desta probabilidade de ser imprópria, a imagem entra, mas vai para a revisão do admin
 * @param blockAt  a partir desta, a imagem é recusada (não é guardada)
 */
@ConfigurationProperties("booksmith.moderation")
record ModerationProperties(String model, boolean required, double reviewAt, double blockAt) {
}
