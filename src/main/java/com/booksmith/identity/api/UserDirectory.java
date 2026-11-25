package com.booksmith.identity.api;

import java.util.Optional;
import java.util.UUID;

/** Dados da conta que outros módulos podem ler (ex.: o suporte responde no e-mail cadastrado). */
public interface UserDirectory {

    /** E-mail cadastrado da conta, se ela existe. */
    Optional<String> email(UUID userId);

    /** Nome que vai como autor nos livros exportados (o de exibição, ou o nome de usuário). */
    Optional<String> displayName(UUID userId);

    /**
     * Nome público (Estante): o nome de autor do perfil; vazio sem perfil. Nunca o nome real nem o nome de usuário
     * (que, sem escolha, é o começo do e-mail): há menores de idade no app.
     */
    Optional<String> publicName(UUID userId);

    /** A conta de um e-mail (o backoffice concede planos pelo e-mail do cliente). */
    Optional<UUID> idByEmail(String email);
}
