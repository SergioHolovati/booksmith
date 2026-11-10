package com.booksmith.identity.api;

import java.util.Optional;
import java.util.UUID;

/** Dados da conta que outros módulos podem ler (ex.: o suporte responde no e-mail cadastrado). */
public interface UserDirectory {

    /** E-mail cadastrado da conta, se ela existe. */
    Optional<String> email(UUID userId);
}
