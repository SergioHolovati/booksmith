package com.booksmith.identity.api;

import java.util.UUID;

/** Usuário autenticado na requisição atual. */
public interface CurrentUser {

    /** @throws com.booksmith.common.error.ApiException 401 se não houver usuário autenticado */
    AuthenticatedUser get();

    default UUID id() {
        return get().id();
    }
}
