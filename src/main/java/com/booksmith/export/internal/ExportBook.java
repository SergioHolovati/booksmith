package com.booksmith.export.internal;

import com.booksmith.media.api.ImageStorage.ImageData;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O livro pronto para virar arquivo: metadados e capítulos já em XHTML.
 *
 * @param fandom só em fanfic: a obra original, para a página de aviso da "edição de fã" (§13.5)
 * @param cover  capa embutida, ou {@code null} sem capa
 */
record ExportBook(UUID id, String title, String author, String language, String synopsis, String fandom,
                  ImageData cover, Instant generatedAt, List<Chapter> chapters) {

    boolean fanfic() {
        return fandom != null;
    }

    /** @param document o documento ProseMirror do capítulo (§8), já validado */
    record Chapter(String title, JsonNode document) {

        /** Corpo do capítulo em XHTML, sem o título (ePub e PDF). */
        String html() {
            return BookHtml.body(document);
        }
    }
}
