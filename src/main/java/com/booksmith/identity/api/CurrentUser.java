package com.booksmith.identity.api;

import java.util.UUID;

/** Usuário autenticado na requisição atual. */
public interface CurrentUser {

    /** @throws com.booksmith.common.error.ApiException 401 se não houver usuário autenticado */
    AuthenticatedUser get();

    /** O usuário da requisição, se houver (rotas públicas, como a leitura na Estante). */
    java.util.Optional<AuthenticatedUser> find();

    default UUID id() {
        return get().id();
    }
}
