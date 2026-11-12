package com.booksmith.notification.api;

public interface EmailSender {

    void sendEmailVerification(String to, String code);

    void sendPasswordReset(String to, String resetLink);

    /** Código da verificação em duas etapas (entrar, ativar ou desativar), válido por 10 minutos. */
    void sendMfaCode(String to, String code);

    /**
     * Aviso de segurança da conta (novo acesso, conta ligada ao Google, login bloqueado por tentativas erradas).
     * Sempre fecha com "se não foi você, troque sua senha".
     *
     * @param title   título e assunto ("Novo acesso à sua conta")
     * @param message o que aconteceu, em texto simples (escapado no HTML)
     */
    void sendSecurityNotice(String to, String title, String message);
}
