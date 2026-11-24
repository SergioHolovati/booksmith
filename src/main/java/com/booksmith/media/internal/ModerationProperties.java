package com.booksmith.media.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A moderação das imagens enviadas (o classificador roda no servidor).
 *
 * @param model    caminho do modelo ONNX; vazio = moderação desligada (só em desenvolvimento)
 * @param required sem o modelo, a API não sobe (produção): nenhuma imagem entra sem ser conferida
 * @param reviewAt a partir desta probabilidade de ser imprópria, a imagem entra, mas vai para a revisão do admin
 * @param blockAt  a partir desta, a imagem é recusada (não é guardada)
 * @param clipModel o CLIP (parte de imagem, ONNX) para violência extrema e símbolos de ódio; vazio = sem essa parte
 * @param goreAt   a partir desta nota de violência extrema, a imagem vai para a revisão (nunca é recusada: violência
 *                 é de livro)
 * @param hateAt   a partir desta nota de símbolo de ódio, a imagem vai para a revisão (idem: romance histórico)
 */
@ConfigurationProperties("booksmith.moderation")
record ModerationProperties(String model, boolean required, double reviewAt, double blockAt, String clipModel,
                            double goreAt, double hateAt) {
}
