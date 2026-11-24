package com.booksmith.identity.api;

/**
 * Registro do que a equipe faz (LGPD: quem mexeu em quê e quando). Grava o funcionário da requisição atual.
 * Só metadados: nunca o texto de um cliente.
 *
 * @see CurrentStaff
 */
public interface StaffAudit {

    /**
     * @param action o que foi feito ("support.reply", "image.remove", "staff.invite"...)
     * @param target sobre o quê (o id ou o protocolo), ou null
     * @param detail um complemento curto, ou null
     */
    void record(String action, String target, String detail);
}
