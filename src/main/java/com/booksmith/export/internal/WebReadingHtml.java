package com.booksmith.export.internal;

import com.booksmith.export.api.ReadingHtml;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** {@link ReadingHtml} com o {@link BookHtml} da exportação; as notas de rodapé vão para o fim do capítulo. */
@Component
class WebReadingHtml implements ReadingHtml {

    @Override
    public String chapter(JsonNode document, java.util.function.Function<String, String> imageSrc) {
        return BookHtml.webBody(document, imageSrc);
    }
}
