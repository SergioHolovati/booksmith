package com.booksmith.manuscript.api;

import java.util.List;
import java.util.Map;
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

    /** A capa importada em PDF do livro, se houver. 404 se o livro não é do usuário. */
    java.util.Optional<CoverPdf> coverPdf(UUID ownerId, UUID bookId);

    /** Livros ativos da pessoa (fora da lixeira), do mais novo ao mais antigo. */
    List<UUID> bookIds(UUID ownerId);

    /** Confere que o livro existe e é do usuário, sem carregar o texto. 404 caso contrário. */
    void requireOwned(UUID ownerId, UUID bookId);

    /** Livros da pessoa que se passam no universo (fora da lixeira), do mais antigo ao mais novo. */
    List<BookSummary> booksInUniverse(UUID ownerId, UUID universeId);

    /**
     * Notas do autor ainda não resolvidas, em ordem de capítulo ("Cap. 3: lembrar que Tomás mente"). É ali que o
     * autor planeja a história. 404 se o livro não é do usuário.
     */
    List<String> openNotes(UUID ownerId, UUID bookId);

    /**
     * O corretor sem IA (LanguageTool, §8.5) em textos do livro que não são capítulos: sinopse, "sobre o autor",
     * fichas. Ignora os nomes do universo e o dicionário do livro, como no editor. Até 60 textos por chamada.
     *
     * @param texts chave (quem chama escolhe) → texto
     * @return chave → erros
     */
    Map<String, List<TextIssue>> proofread(UUID ownerId, UUID bookId, Map<String, String> texts);

    /** Um erro do corretor: onde está no texto, o tipo (spelling, grammar, style) e as sugestões. */
    record TextIssue(int offset, int length, String type, String message, List<String> replacements) {
    }

    record BookSummary(UUID bookId, String title, String synopsis) {
    }

    record BookText(UUID bookId, String title, UUID universeId, List<ChapterText> chapters) {
    }

    /**
     * {@code language} é a tag BCP 47 (ex.: "pt-BR"); {@code fandom} só existe em fanfic. {@code layout}: a
     * diagramação do PDF.
     */
    record BookContent(UUID bookId, String title, String synopsis, String language, boolean fanfic, String fandom,
                       String coverUrl, UUID universeId, List<ChapterContent> chapters, BookPages pages,
                       BookLayout layout) {

        public BookContent(UUID bookId, String title, String synopsis, String language, boolean fanfic, String fandom,
                           String coverUrl, UUID universeId, List<ChapterContent> chapters) {
            this(bookId, title, synopsis, language, fanfic, fandom, coverUrl, universeId, chapters, BookPages.EMPTY,
                    BookLayout.DEFAULT);
        }
    }

    /**
     * As páginas antes e depois da história (§4.7 do produto), em texto simples: cada linha vira um parágrafo.
     * Tudo opcional; null = a página não sai no livro.
     *
     * @param credits        verso da folha de rosto: direitos, capa, ilustrações, revisão
     * @param epigraphSource   de quem é a epígrafe ("Clarice Lispector, A hora da estrela")
     * @param aboutAuthorTitle título da página "sobre": "Sobre a autora", "Sobre mim"; null = o nome do autor
     */
    record BookPages(String credits, String dedication, String epigraph, String epigraphSource,
                     String acknowledgments, String aboutAuthor, String aboutAuthorTitle) {

        public static final BookPages EMPTY = new BookPages(null, null, null, null, null, null, null);

        /** Texto limpo: sem espaços nas pontas, quebras de linha unificadas, vazio vira null. */
        public BookPages {
            credits = clean(credits);
            dedication = clean(dedication);
            epigraph = clean(epigraph);
            epigraphSource = epigraph == null ? null : clean(epigraphSource);
            acknowledgments = clean(acknowledgments);
            aboutAuthor = clean(aboutAuthor);
            aboutAuthorTitle = aboutAuthor == null || aboutAuthorTitle == null
                    ? null : clean(aboutAuthorTitle.replace('\n', ' ').replace('\r', ' '));
        }

        private static String clean(String text) {
            if (text == null) {
                return null;
            }
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
            return normalized.isEmpty() ? null : normalized;
        }
    }

    /**
     * {@code document} é o JSON ProseMirror do capítulo, já validado contra o esquema (§8). {@code partTitle}: o
     * capítulo abre uma parte do livro (null = continua a anterior).
     */
    record ChapterContent(UUID chapterId, int position, String title, String document, String partTitle) {
    }

    record ChapterText(UUID chapterId, int position, String title, List<Block> blocks) {
    }

    /** Um parágrafo ou título: o id estável do bloco e o texto puro (quebras de linha viram espaço). */
    record Block(String blockId, String text) {
    }
}
