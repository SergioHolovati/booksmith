package com.booksmith.identity.api;

/** O funcionário autenticado na requisição atual, nas rotas da equipe ({@code /api/v1/admin/**}). */
public interface CurrentStaff {

    /** @throws com.booksmith.common.error.ApiException 401 sem funcionário autenticado */
    AuthenticatedStaff get();

    /**
     * O funcionário, se tiver um dos papéis (o administrador passa sempre).
     *
     * @throws com.booksmith.common.error.ApiException 403 {@code staff_role} se o papel não permite
     */
    AuthenticatedStaff require(StaffRole... roles);
}
