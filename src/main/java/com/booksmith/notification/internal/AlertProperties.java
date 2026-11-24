package com.booksmith.notification.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Para onde vão os alertas de segurança e a partir de quantos eventos por hora cada um dispara.
 *
 * @param email               quem recebe (vazio: só o log, útil em desenvolvimento)
 * @param loginLockedPerHour  contas bloqueadas por senha errada
 * @param rateLimitedPerHour  respostas 429 do limite por IP
 * @param mfaFailedPerHour    desafios de MFA encerrados por códigos errados
 * @param serverErrorsPerHour erros 500
 * @param quotaHitsPerHour    limites diários de PDF atingidos
 * @param imagesFlaggedPerHour imagens que a moderação mandou para revisão ou recusou (1: avisa a cada hora com alguma)
 * @param staffDomainRejectedPerHour e-mails de fora do domínio tentando entrar na equipe (1: avisa a cada hora com algum)
 */
@ConfigurationProperties("booksmith.alerts")
public record AlertProperties(
        String email,
        int loginLockedPerHour,
        int rateLimitedPerHour,
        int mfaFailedPerHour,
        int serverErrorsPerHour,
        int quotaHitsPerHour,
        int imagesFlaggedPerHour,
        int staffDomainRejectedPerHour
) {
}
