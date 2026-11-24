package com.booksmith.support.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Atendimento.
 *
 * @param teamEmail quem recebe o aviso de pedido novo e de resposta da pessoa (vazio: só o log)
 * @param appUrl    endereço do app, para o link "ver a resposta" no e-mail da pessoa
 * @param adminUrl  endereço do backoffice, para o link no e-mail da equipe
 */
@ConfigurationProperties("booksmith.support")
public record SupportProperties(String teamEmail, String appUrl, String adminUrl) {
}
