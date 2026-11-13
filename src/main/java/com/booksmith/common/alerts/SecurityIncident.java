package com.booksmith.common.alerts;

/**
 * Um sinal de que algo pode estar errado, publicado como evento do Spring por quem o vê (login bloqueado, limite por
 * IP, erro inesperado, MFA errado demais). Quem conta e avisa é o módulo de notificação ({@code SecurityAlerts}).
 * Só metadados: nunca o e-mail, o código ou o texto.
 */
public record SecurityIncident(Kind kind, String detail) {

    public enum Kind {
        /** Conta bloqueada por 5 senhas erradas. */
        LOGIN_LOCKED,
        /** Um IP passou do limite de requisições por minuto. */
        RATE_LIMITED,
        /** Desafio do MFA encerrado por 5 códigos errados. */
        MFA_FAILED,
        /** Erro 500 na API. */
        SERVER_ERROR,
        /** Pessoa bateu num limite diário (PDF). */
        QUOTA_HIT
    }
}
