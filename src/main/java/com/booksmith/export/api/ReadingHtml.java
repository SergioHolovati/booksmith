package com.booksmith.export.api;

import tools.jackson.databind.JsonNode;

/**
 * O documento do editor (ProseMirror) em HTML para ler na web (Estante). É o mesmo gerador do ePub e do PDF: só os
 * nós do esquema do editor viram HTML, e todo texto sai escapado (nunca HTML vindo do cliente).
 */
public interface ReadingHtml {

    /** O corpo do capítulo, sem o título. Ilustrações saem só com a legenda. */
    default String chapter(JsonNode document) {
        return chapter(document, id -> null);
    }

    /**
     * Idem, com a imagem de cada ilustração: {@code imageSrc(imageId)} devolve o endereço público, ou null para sair
     * só a legenda (imagem que não existe mais ou de outra pessoa).
     */
    String chapter(JsonNode document, java.util.function.Function<String, String> imageSrc);
}
