package com.booksmith.manuscript.api;

import java.util.List;
import java.util.UUID;

/** Leitura do texto do livro por outros módulos (continuidade, exportação), sempre em nome do dono. */
public interface Manuscripts {

    /**
     * Texto do livro, capítulo a capítulo e bloco a bloco (parágrafos e títulos, com os ids estáveis).
     *
     * @throws com.booksmith.common.error.ApiException 404 se o livro não existe ou é de outra pessoa
     */
    BookText bookText(UUID ownerId, UUID bookId);

    /**
     * O livro completo para exportação: metadados e o documento ProseMirror (JSON) de cada capítulo, em ordem.
     *
     * @throws com.booksmith.common.error.ApiException 404 se o livro não existe ou é de outra pessoa
     */
    BookContent bookContent(UUID ownerId, UUID bookId);

    /** Livros ativos da pessoa (fora da lixeira), do mais novo ao mais antigo. */
    java.util.List<UUID> bookIds(UUID ownerId);

    /** Confere que o livro existe e é do usuário, sem carregar o texto. 404 caso contrário. */
    void requireOwned(UUID ownerId, UUID bookId);

    record BookText(UUID bookId, String title, UUID universeId, List<ChapterText> chapters) {
    }

    /** {@code language} é a tag BCP 47 (ex.: "pt-BR"); {@code fandom} só existe em fanfic. */
    record BookContent(UUID bookId, String title, String synopsis, String language, boolean fanfic, String fandom,
                       String coverUrl, List<ChapterContent> chapters) {
    }

    /** {@code document} é o JSON ProseMirror do capítulo, já validado contra o esquema (§8). */
    record ChapterContent(UUID chapterId, int position, String title, String document) {
    }

    record ChapterText(UUID chapterId, int position, String title, List<Block> blocks) {
    }

    /** Um parágrafo ou título: o id estável do bloco e o texto puro (quebras de linha viram espaço). */
    record Block(String blockId, String text) {
    }
}
