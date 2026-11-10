package com.booksmith.common.data;

import java.util.UUID;

/**
 * Parte de um módulo no "Baixar tudo": cada módulo escreve os dados da pessoa que guarda, sem depender dos outros.
 * Só o que existe hoje (nada da lixeira) e nunca dados internos (senha, códigos, tokens).
 */
public interface PersonalDataContributor {

    /** Nome curto do que este módulo grava, para o log ("livros", "universos"). */
    String name();

    void contribute(UUID ownerId, PersonalDataArchive archive);
}
