package com.booksmith.export.api;

import tools.jackson.databind.JsonNode;

/**
 * O documento do editor (ProseMirror) em HTML para ler na web (Estante). É o mesmo gerador do ePub e do PDF: só os
 * nós do esquema do editor viram HTML, e todo texto sai escapado (nunca HTML vindo do cliente).
 */
public interface ReadingHtml {

    /** O corpo do capítulo, sem o título. Ilustrações saem só com a legenda (as imagens ficam para depois). */
    String chapter(JsonNode document);
}
