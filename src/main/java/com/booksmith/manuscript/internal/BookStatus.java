package com.booksmith.manuscript.internal;

/** Estado do rascunho. Publicação é responsabilidade do módulo da Estante (§14), não do manuscrito. */
public enum BookStatus {
    DRAFT,
    WRITING,
    COMPLETE
}
