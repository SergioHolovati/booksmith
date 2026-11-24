package com.booksmith.identity.api;

/** Papel de quem é da equipe (backoffice). Contas da equipe são separadas das contas dos clientes. */
public enum StaffRole {
    /** Tudo, inclusive cadastrar a equipe e os convites do beta. */
    ADMIN,
    /** Suporte e monitoramento. */
    SUPPORT,
    /** Moderação de imagens. */
    MODERATOR
}
