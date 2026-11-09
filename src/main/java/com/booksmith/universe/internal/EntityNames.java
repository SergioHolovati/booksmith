package com.booksmith.universe.internal;

import com.booksmith.common.error.ApiException;

/** Nome de personagem, lugar e demais entidades: obrigatório, até 120 caracteres, sem espaços nas pontas. */
final class EntityNames {

    static final int MAX_NAME = 120;

    private EntityNames() {
    }

    static String require(String name, String code, String missingMessage) {
        if (name == null || name.isBlank()) {
            throw ApiException.unprocessable(code, missingMessage);
        }
        String trimmed = name.strip();
        if (trimmed.length() > MAX_NAME) {
            throw ApiException.unprocessable(code, "O nome pode ter até %d caracteres.".formatted(MAX_NAME));
        }
        return trimmed;
    }
}
