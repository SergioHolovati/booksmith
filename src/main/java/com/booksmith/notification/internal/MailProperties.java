package com.booksmith.notification.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled      false em desenvolvimento: o conteúdo do e-mail vai para o log em vez de ser enviado
 * @param resendApiKey chave da API do Resend (somente por variável de ambiente)
 * @param from         remetente
 */
@ConfigurationProperties("booksmith.mail")
record MailProperties(boolean enabled, String resendApiKey, String from) {
}
