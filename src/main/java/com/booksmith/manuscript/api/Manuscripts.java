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

    /** Dados do livro para a capa com IA (título, sinopse, universo, se é fanfic e se está concluído). Vazio se não é do usuário. */
    java.util.Optional<BookBrief> bookBrief(UUID ownerId, UUID bookId);

    record BookBrief(UUID bookId, String title, String synopsis, UUID universeId, boolean fanfic, boolean complete) {
    }

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

    /** Troca a sinopse (correções da revisão dos textos do livro). */
    void replaceSynopsis(UUID ownerId, UUID bookId, String synopsis);

    /** Troca o "sobre o autor"; as outras páginas do livro ficam como estão. */
    void replaceAboutAuthor(UUID ownerId, UUID bookId, String aboutAuthor);

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
    /** {@code genre}: o gênero do livro (FICTION, CHILDREN…); no CHILDREN, os capítulos são as páginas ilustradas. */
    record BookContent(UUID bookId, String title, String synopsis, String language, boolean fanfic, String fandom,
                       String coverUrl, UUID universeId, List<ChapterContent> chapters, BookPages pages,
                       BookLayout layout, String genre) {

        public BookContent(UUID bookId, String title, String synopsis, String language, boolean fanfic, String fandom,
                           String coverUrl, UUID universeId, List<ChapterContent> chapters, BookPages pages,
                           BookLayout layout) {
            this(bookId, title, synopsis, language, fanfic, fandom, coverUrl, universeId, chapters, pages, layout,
                    "FICTION");
        }

        /** Livro de páginas: o infantil, ou outro gênero no modo páginas (o livro pronto importado em PDF). */
        public boolean pictureBook() {
            return "CHILDREN".equals(genre) || (layout != null && layout.isFixedLayout());
        }

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
     * @param halfTitle        falsa folha de rosto: uma página só com o título, antes da folha de rosto
     * @param catalogCard      ficha catalográfica (dados de catalogação na publicação), no verso da folha de rosto
     * @param colophon         colofão: a última página (tipografia, papel, gráfica, data)
     * @param authorPhotoId    foto de quem escreveu (da biblioteca do universo do livro), na página "sobre" e na
     *                         contracapa
     * @param isbnPrint        ISBN do impresso (guia de publicação), nos créditos do PDF; só na exportação
     * @param isbnEbook        ISBN do e-book, nos créditos e no identificador do ePub; só na exportação (os dois ficam
     *                         fora do JSON: na API, o ISBN é do guia de publicação)
     */
    record BookPages(String credits, String dedication, String epigraph, String epigraphSource,
                     String acknowledgments, String aboutAuthor, String aboutAuthorTitle, Boolean halfTitle,
                     String catalogCard, String colophon, UUID authorPhotoId,
                     @com.fasterxml.jackson.annotation.JsonIgnore String isbnPrint,
                     @com.fasterxml.jackson.annotation.JsonIgnore String isbnEbook,
                     @com.fasterxml.jackson.annotation.JsonIgnore List<ExtraPage> extras) {

        /** Sem a organização das páginas geradas (o lugar de sempre, o fundo da diagramação). */
        public BookPages(String credits, String dedication, String epigraph, String epigraphSource,
                         String acknowledgments, String aboutAuthor, String aboutAuthorTitle, Boolean halfTitle,
                         String catalogCard, String colophon, UUID authorPhotoId, String isbnPrint, String isbnEbook) {
            this(credits, dedication, epigraph, epigraphSource, acknowledgments, aboutAuthor, aboutAuthorTitle,
                    halfTitle, catalogCard, colophon, authorPhotoId, isbnPrint, isbnEbook, null);
        }

        public static final BookPages EMPTY = new BookPages(null, null, null, null, null, null, null);

        /** As páginas que o autor escreve, sem ISBN (o ISBN vem do guia de publicação). */
        public BookPages(String credits, String dedication, String epigraph, String epigraphSource,
                         String acknowledgments, String aboutAuthor, String aboutAuthorTitle) {
            this(credits, dedication, epigraph, epigraphSource, acknowledgments, aboutAuthor, aboutAuthorTitle, null,
                    null, null, null);
        }

        /** Com a estrutura completa (falsa folha de rosto, ficha, colofão, foto), sem ISBN. */
        public BookPages(String credits, String dedication, String epigraph, String epigraphSource,
                         String acknowledgments, String aboutAuthor, String aboutAuthorTitle, Boolean halfTitle,
                         String catalogCard, String colophon, UUID authorPhotoId) {
            this(credits, dedication, epigraph, epigraphSource, acknowledgments, aboutAuthor, aboutAuthorTitle,
                    halfTitle, catalogCard, colophon, authorPhotoId, null, null);
        }

        /** Os modelos das páginas opcionais: o autor completa, ou apaga para a página sair do livro. */
        public static final String DEDICATION_TEMPLATE = "[Para quem é este livro? Ex.: Para a Lia, que me ensinou a ver cores.]";
        public static final String EPIGRAPH_TEMPLATE = "[Uma frase que abre o livro, de um livro, música ou pessoa]";
        public static final String EPIGRAPH_SOURCE_TEMPLATE = "[De quem é a frase]";
        public static final String ACKNOWLEDGMENTS_TEMPLATE =
                "[A quem você quer agradecer: quem leu antes, revisou, ilustrou, apoiou.]";

        /**
         * Os modelos com que todo livro novo nasce: o que fica entre colchetes é para o autor completar. Linha com
         * colchete que sobrou não sai no livro ({@link #filled()}) e a conferência para a gráfica avisa.
         */
        public static final BookPages TEMPLATE = new BookPages(
                "Edição: [editora, ou edição independente]\n"
                        + "Revisão: [nome de quem revisou]\n"
                        + "Ilustração: [nome de quem ilustrou]\n"
                        + "Capa: [nome de quem fez a capa]\n"
                        + "Projeto gráfico e diagramação: [nome]\n"
                        + "[Cidade], [ano]",
                DEDICATION_TEMPLATE, EPIGRAPH_TEMPLATE, EPIGRAPH_SOURCE_TEMPLATE, ACKNOWLEDGMENTS_TEMPLATE,
                "[Conte em poucas linhas quem você é: onde vive, o que faz, o que escreve e por que escreveu este livro.]",
                null, null, null,
                "Este livro foi composto em [fonte] e impresso em [papel] pela [gráfica], em [mês e ano].", null);

        private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\[[^\\]\\n]{1,120}\\]");

        /** As linhas que ainda têm um campo do modelo para completar ("[nome de quem revisou]"). */
        public static List<String> pending(String text) {
            if (text == null) {
                return List.of();
            }
            return text.lines().map(String::strip).filter(line -> PLACEHOLDER.matcher(line).find()).toList();
        }

        /** As páginas sem as linhas do modelo que ficaram por completar (elas não saem no livro). */
        public BookPages filled() {
            return new BookPages(unfilledRemoved(credits), unfilledRemoved(dedication), unfilledRemoved(epigraph), unfilledRemoved(epigraphSource),
                    unfilledRemoved(acknowledgments), unfilledRemoved(aboutAuthor), unfilledRemoved(aboutAuthorTitle), halfTitle, unfilledRemoved(catalogCard),
                    unfilledRemoved(colophon), authorPhotoId, isbnPrint, isbnEbook, extras);
        }

        private static String unfilledRemoved(String text) {
            if (text == null) {
                return null;
            }
            String kept = String.join("\n", text.lines().filter(line -> !PLACEHOLDER.matcher(line).find()).toList())
                    .strip();
            return kept.isEmpty() ? null : kept;
        }

        /** As mesmas páginas com os ISBNs do guia de publicação, que saem nos créditos. */
        public BookPages withIsbn(String print, String ebook) {
            return new BookPages(credits, dedication, epigraph, epigraphSource, acknowledgments, aboutAuthor,
                    aboutAuthorTitle, halfTitle, catalogCard, colophon, authorPhotoId, print, ebook, extras);
        }

        /** As mesmas páginas com a organização das que o Booksmith gera no livro ilustrado. */
        public BookPages withExtras(List<ExtraPage> pages) {
            return new BookPages(credits, dedication, epigraph, epigraphSource, acknowledgments, aboutAuthor,
                    aboutAuthorTitle, halfTitle, catalogCard, colophon, authorPhotoId, isbnPrint, isbnEbook, pages);
        }

        /**
         * As páginas que o Booksmith gera no livro ilustrado ({@link ExtraPage#KEYS}) que este livro tem: a folha de
         * rosto, os créditos e a página de rosto sempre; as outras quando têm texto, mesmo o modelo por completar (na
         * exportação, as páginas já chegam sem o modelo: a que só tinha modelo não sai).
         */
        public List<String> extraKeys() {
            BookPages f = this;
            List<String> keys = new java.util.ArrayList<>(List.of("cover", "credits", "title"));
            if (f.dedication() != null) {
                keys.add("dedication");
            }
            if (f.epigraph() != null) {
                keys.add("epigraph");
            }
            if (f.acknowledgments() != null) {
                keys.add("acknowledgments");
            }
            if (f.aboutAuthor() != null) {
                keys.add("about");
            }
            if (f.colophon() != null) {
                keys.add("colophon");
            }
            return keys;
        }

        /** As páginas geradas que o livro tem, na ordem e no lugar escolhidos ({@link ExtraPage#arrange}). */
        public List<ExtraPage> arrangedExtras(java.util.Collection<String> chapterIds) {
            return ExtraPage.arrange(extras, extraKeys(), chapterIds);
        }

        public boolean hasHalfTitle() {
            return Boolean.TRUE.equals(halfTitle);
        }

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
            halfTitle = Boolean.TRUE.equals(halfTitle);
            catalogCard = clean(catalogCard);
            colophon = clean(colophon);
            extras = extras == null ? List.of() : List.copyOf(extras);
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
    record ChapterContent(UUID chapterId, int position, String title, String document, String partTitle,
                          PageDesign page) {

        public ChapterContent(UUID chapterId, int position, String title, String document, String partTitle) {
            this(chapterId, position, title, document, partTitle, null);
        }
    }

    record ChapterText(UUID chapterId, int position, String title, List<Block> blocks) {
    }

    /** Um parágrafo ou título: o id estável do bloco e o texto puro (quebras de linha viram espaço). */
    record Block(String blockId, String text) {
    }
}
