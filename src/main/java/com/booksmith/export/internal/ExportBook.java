package com.booksmith.export.internal;

import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.manuscript.api.Manuscripts.BookPages;
import com.booksmith.media.api.ImageStorage.ImageData;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O livro pronto para virar arquivo: metadados e capítulos já em XHTML.
 *
 * @param fandom só em fanfic: a obra original, para a página de aviso da "edição de fã" (§13.5)
 * @param cover         capa embutida, ou {@code null} sem capa
 * @param illustrations ilustrações do texto (id da imagem da biblioteca → arquivo); imagem que sumiu fica de fora
 * @param pages         créditos, dedicatória, epígrafe, agradecimentos e "sobre o autor" (vazias = não saem)
 * @param layout        diagramação do PDF (tamanho, letra, margens, abertura, quebra de cena)
 * @param pictureBook   livro ilustrado (infantil): cada capítulo é uma página com o fundo e o texto por cima
 */
record ExportBook(UUID id, String title, String author, String language, String synopsis, String fandom,
                  ImageData cover, Instant generatedAt, List<Chapter> chapters,
                  java.util.Map<String, ImageData> illustrations, BookPages pages, BookLayout layout,
                  boolean pictureBook) {

    /** Livro de texto (não ilustrado). */
    ExportBook(UUID id, String title, String author, String language, String synopsis, String fandom,
               ImageData cover, Instant generatedAt, List<Chapter> chapters,
               java.util.Map<String, ImageData> illustrations, BookPages pages, BookLayout layout) {
        this(id, title, author, language, synopsis, fandom, cover, generatedAt, chapters, illustrations, pages, layout,
                false);
    }

    /** Livro com a diagramação padrão. */
    ExportBook(UUID id, String title, String author, String language, String synopsis, String fandom,
               ImageData cover, Instant generatedAt, List<Chapter> chapters,
               java.util.Map<String, ImageData> illustrations, BookPages pages) {
        this(id, title, author, language, synopsis, fandom, cover, generatedAt, chapters, illustrations, pages,
                BookLayout.DEFAULT);
    }

    /**
     * O mesmo livro com as ilustrações reduzidas (lado maior até {@code maxSide} px, JPEG): para a prévia, que só
     * mostra as páginas na tela. A de impressão (o PDF baixado) usa as originais.
     */
    ExportBook withPreviewImages(int maxSide) {
        java.util.Map<String, ImageData> small = new java.util.HashMap<>();
        illustrations.forEach((id, image) -> small.put(id, PreviewImages.shrink(image, maxSide)));
        return new ExportBook(id, title, author, language, synopsis, fandom, cover, generatedAt, chapters, small, pages,
                layout, pictureBook);
    }

    /** O mesmo livro com outra diagramação (prévia de escolhas ainda não salvas). */
    ExportBook withLayout(BookLayout other) {
        return new ExportBook(id, title, author, language, synopsis, fandom, cover, generatedAt, chapters, illustrations,
                pages, other, pictureBook);
    }

    /** Livro sem páginas extras. */
    ExportBook(UUID id, String title, String author, String language, String synopsis, String fandom,
               ImageData cover, Instant generatedAt, List<Chapter> chapters,
               java.util.Map<String, ImageData> illustrations) {
        this(id, title, author, language, synopsis, fandom, cover, generatedAt, chapters, illustrations, BookPages.EMPTY);
    }

    /** Livro sem ilustrações (testes e chamadas antigas). */
    ExportBook(UUID id, String title, String author, String language, String synopsis, String fandom,
               ImageData cover, Instant generatedAt, List<Chapter> chapters) {
        this(id, title, author, language, synopsis, fandom, cover, generatedAt, chapters, java.util.Map.of());
    }

    boolean fanfic() {
        return fandom != null;
    }

    /**
     * @param document  o documento ProseMirror do capítulo (§8), já validado
     * @param partTitle o capítulo abre uma parte do livro ("Parte I — A partida"); null = continua a anterior
     * @param page      no livro ilustrado, o desenho da página (fundo, texto); null nos outros
     */
    record Chapter(String title, JsonNode document, String partTitle, com.booksmith.manuscript.api.PageDesign page) {

        Chapter(String title, JsonNode document, String partTitle) {
            this(title, document, partTitle, null);
        }

        Chapter(String title, JsonNode document) {
            this(title, document, null);
        }

        /**
         * O texto para ler em sequência (ePub, Word): no livro ilustrado, a quebra entre os blocos de texto da página
         * só separa os blocos no desenho; aqui ela some, e os blocos ficam um depois do outro.
         */
        JsonNode reading() {
            if (page == null || !(document instanceof tools.jackson.databind.node.ObjectNode doc)
                    || !(doc.get("content") instanceof tools.jackson.databind.node.ArrayNode content)) {
                return document;
            }
            tools.jackson.databind.node.ObjectNode copy = doc.deepCopy();
            tools.jackson.databind.node.ArrayNode kept = copy.putArray("content");
            for (JsonNode node : content) {
                if (!"sceneBreak".equals(node.path("type").asString(""))) {
                    kept.add(node.deepCopy());
                }
            }
            return copy;
        }

        /** Corpo do capítulo em XHTML, sem o título (ePub e PDF). */
        String html() {
            return BookHtml.body(reading());
        }

        /** Idem, com o endereço de cada ilustração ({@code src(imageId)}; null = só a legenda). */
        String html(java.util.function.Function<String, String> src) {
            return BookHtml.body(reading(), src);
        }

        /** Idem, com o id {@code <idPrefix>-s<n>} em cada título interno (para o sumário). */
        String html(java.util.function.Function<String, String> src, String idPrefix) {
            return BookHtml.body(reading(), src, idPrefix);
        }

        /** Idem, com quem desenha as ilustrações de página própria (o PDF). */
        String html(java.util.function.Function<String, String> src, String idPrefix, BookHtml.PageIllustration pages) {
            return BookHtml.body(reading(), src, idPrefix, pages);
        }

        /**
         * Os subcapítulos que entram no sumário: os títulos internos de nível 1 e 2 (os do Word "Título 2" e os
         * subcapítulos da importação). Os de nível 3 ficam só no texto.
         */
        List<BookHtml.Section> sections() {
            return document == null ? List.of() : BookHtml.sections(document).stream()
                    .filter(s -> s.level() <= 2 && !s.title().isEmpty()).toList();
        }
    }
}
