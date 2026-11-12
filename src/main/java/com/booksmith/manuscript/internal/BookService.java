package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.common.text.SlugUtils;
import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.manuscript.api.CoverPdf;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.media.api.ImageCategory;
import com.booksmith.media.api.ImageStorage;
import com.booksmith.media.api.PdfStorage;
import com.booksmith.universe.api.Universes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookService {

    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final ImageStorage imageStorage;
    private final Universes universes;
    private final Clock clock;
    private final JsonMapper jsonMapper;
    private final PdfStorage pdfStorage;

    @Transactional(readOnly = true)
    public Page<BookWithStats> list(UUID ownerId, Pageable pageable) {
        Page<Book> books = bookRepository.findAllByOwnerIdAndDeletedAtIsNull(ownerId, pageable);
        Map<UUID, BookStats> stats = chapterRepository.statsFor(books.map(Book::getId).getContent()).stream()
                .collect(Collectors.toMap(BookStats::bookId, Function.identity()));
        return books.map(book -> new BookWithStats(book, stats.getOrDefault(book.getId(), BookStats.empty(book.getId()))));
    }

    @Transactional(readOnly = true)
    public BookDetail get(UUID ownerId, UUID bookId) {
        Book book = require(ownerId, bookId);
        return new BookDetail(book, chapterRepository.findAllByBookIdAndDeletedAtIsNullOrderByPositionAsc(bookId),
                layoutOf(book), coverDesignOf(book), coverPdfOf(book));
    }

    @Transactional
    public Book create(UUID ownerId, BookData data) {
        validate(data);
        Book book = Book.builder()
                .ownerId(ownerId)
                .status(data.status() == null ? BookStatus.DRAFT : data.status())
                .language(data.language() == null ? BookLanguage.DEFAULT : data.language())
                .build();
        apply(book, data);
        if (data.coverDataUrl() != null) {
            book.setCoverUrl(imageStorage.store(ImageCategory.BOOK_COVER, ownerId, data.coverDataUrl()).url());
        }
        // Personagens e lugares do livro moram no universo dele: um novo, ou o de uma série que já existe
        if (data.universeId() != null) {
            universes.requireOwned(ownerId, data.universeId());
            book.setUniverseId(data.universeId());
        } else {
            book.setUniverseId(universes.create(ownerId, book.getTitle()));
        }
        return bookRepository.save(book);
    }

    @Transactional
    public Book update(UUID ownerId, UUID bookId, BookData data) {
        validate(data);
        Book book = require(ownerId, bookId);
        apply(book, data);
        if (data.status() != null) {
            book.setStatus(data.status());
        }
        if (data.language() != null) {
            book.setLanguage(data.language());
        }
        if (data.coverDataUrl() != null) {
            String previousCover = book.getCoverUrl();
            book.setCoverUrl(imageStorage.store(ImageCategory.BOOK_COVER, ownerId, data.coverDataUrl()).url());
            imageStorage.delete(previousCover);
        }
        return book;
    }

    /** Só a sinopse (correções da revisão dos textos do livro). */
    @Transactional
    public void replaceSynopsis(UUID ownerId, UUID bookId, String synopsis) {
        require(ownerId, bookId).setSynopsis(synopsis);
    }

    /** Só o "sobre o autor": as outras páginas ficam. */
    @Transactional
    public void replaceAboutAuthor(UUID ownerId, UUID bookId, String aboutAuthor) {
        Book book = require(ownerId, bookId);
        Manuscripts.BookPages p = book.pages();
        book.setPages(new Manuscripts.BookPages(p.credits(), p.dedication(), p.epigraph(), p.epigraphSource(),
                p.acknowledgments(), aboutAuthor, p.aboutAuthorTitle()));
    }

    /** Troca as páginas antes e depois da história (créditos, dedicatória...); vazio tira a página do livro. */
    @Transactional
    public Manuscripts.BookPages updatePages(UUID ownerId, UUID bookId, Manuscripts.BookPages pages) {
        Book book = require(ownerId, bookId);
        book.setPages(pages);
        return book.pages();
    }

    /**
     * Capa feita no editor: guarda a imagem desenhada no aparelho como a capa do livro (a anterior sai do
     * armazenamento) e o desenho, para editar depois.
     *
     * @param imageDataUrl JPEG ou PNG, os formatos que o ePub e o PDF embutem
     * @throws ApiException 422 {@code cover_image_invalid} com outro formato; {@code cover_image_not_found} se a
     *                      imagem de fundo não é da biblioteca do universo do livro
     */
    @Transactional
    public CoverDesign saveCover(UUID ownerId, UUID bookId, CoverDesign design, String imageDataUrl) {
        Book book = require(ownerId, bookId);
        if (imageDataUrl == null
                || !(imageDataUrl.startsWith("data:image/jpeg;") || imageDataUrl.startsWith("data:image/png;"))) {
            throw ApiException.unprocessable("cover_image_invalid", "A capa precisa ser uma imagem JPEG ou PNG.");
        }
        if (design.imageId() != null && (book.getUniverseId() == null
                || universes.imageUrl(book.getUniverseId(), design.imageId()).isEmpty())) {
            throw ApiException.unprocessable("cover_image_not_found",
                    "A imagem de fundo não está mais na biblioteca do universo.");
        }
        String previous = book.getCoverUrl();
        book.setCoverUrl(imageStorage.store(ImageCategory.BOOK_COVER, ownerId, imageDataUrl).url());
        imageStorage.delete(previous);
        book.setCoverDesign(jsonMapper.writeValueAsString(design));
        // A capa agora é a desenhada aqui: o PDF importado antes deixa de valer (inclusive para a gráfica)
        CoverPdf imported = coverPdfOf(book);
        if (imported != null) {
            pdfStorage.delete(imported.url());
            book.setCoverPdf(null);
        }
        return design;
    }

    /**
     * Capa feita fora do app, em PDF: o original fica guardado (para a capa de gráfica) e a frente vira a imagem
     * de capa do livro (app, ePub e PDF). Substitui o PDF anterior.
     *
     * @throws ApiException 422 {@code cover_pdf_invalid}, {@code cover_pdf_protected}, {@code pdf_invalid} ou
     *                      {@code pdf_too_large}
     */
    @Transactional
    public CoverPdf importCoverPdf(UUID ownerId, UUID bookId, String fileName, byte[] pdf) {
        Book book = require(ownerId, bookId);
        CoverPdfReader.Result read = CoverPdfReader.read(pdf, layoutOf(book));
        String url = pdfStorage.store(ownerId, pdf);
        String previousCover = book.getCoverUrl();
        book.setCoverUrl(imageStorage.store(ImageCategory.BOOK_COVER, ownerId, read.frontImage()).url());
        imageStorage.delete(previousCover);
        CoverPdf previous = coverPdfOf(book);
        if (previous != null) {
            pdfStorage.delete(previous.url());
        }
        String name = fileName == null || fileName.isBlank() ? "capa.pdf" : fileName.strip();
        CoverPdf cover = new CoverPdf(url, read.kind(), read.widthMm(), read.heightMm(),
                name.length() > 120 ? name.substring(0, 120) : name);
        book.setCoverPdf(jsonMapper.writeValueAsString(cover));
        return cover;
    }

    /** Tira o PDF importado (a imagem de capa que veio dele continua). */
    @Transactional
    public void removeCoverPdf(UUID ownerId, UUID bookId) {
        Book book = require(ownerId, bookId);
        CoverPdf previous = coverPdfOf(book);
        if (previous != null) {
            pdfStorage.delete(previous.url());
            book.setCoverPdf(null);
        }
    }

    /** O PDF de capa importado, ou null. */
    public CoverPdf coverPdfOf(Book book) {
        return book.getCoverPdf() == null ? null : jsonMapper.readValue(book.getCoverPdf(), CoverPdf.class);
    }

    /**
     * Guarda só o desenho (texto da contracapa, cores), sem trocar a imagem de capa: usado quando a frente veio
     * de um PDF importado.
     */
    @Transactional
    public CoverDesign saveCoverDesign(UUID ownerId, UUID bookId, CoverDesign design) {
        Book book = require(ownerId, bookId);
        book.setCoverDesign(jsonMapper.writeValueAsString(design));
        return design;
    }

    /** O desenho da capa guardado, ou null se a capa não foi feita no editor. */
    public CoverDesign coverDesignOf(Book book) {
        return book.getCoverDesign() == null ? null : jsonMapper.readValue(book.getCoverDesign(), CoverDesign.class);
    }

    /** Troca a diagramação do PDF; o que vier nulo volta ao padrão. */
    @Transactional
    public BookLayout updateLayout(UUID ownerId, UUID bookId, BookLayout layout) {
        Book book = require(ownerId, bookId);
        book.setLayout(jsonMapper.writeValueAsString(layout));
        return layout;
    }

    /** A diagramação guardada no livro (sempre válida: só entra pelo {@link #updateLayout}). */
    public BookLayout layoutOf(Book book) {
        return book.getLayout() == null ? BookLayout.DEFAULT : jsonMapper.readValue(book.getLayout(), BookLayout.class);
    }

    /**
     * Leva o livro para outro universo, para que use os mesmos personagens, lugares e linha do tempo.
     * Se nenhum outro livro usa o universo de onde ele sai, esse universo inteiro é juntado ao de destino
     * (o que o autor já criou vai junto, nada se perde). Se outros livros ainda o usam, só o livro muda.
     */
    @Transactional
    public UniverseMove moveToUniverse(UUID ownerId, UUID bookId, UUID targetUniverseId) {
        Book book = require(ownerId, bookId);
        UUID current = book.getUniverseId();
        universes.requireOwned(ownerId, targetUniverseId);
        if (targetUniverseId.equals(current)) {
            return new UniverseMove(current, false, List.of());
        }
        if (current == null || bookRepository.countByUniverseIdAndIdNotAndDeletedAtIsNull(current, bookId) > 0) {
            book.setUniverseId(targetUniverseId);
            return new UniverseMove(targetUniverseId, false, List.of());
        }
        bookRepository.moveUniverse(current, targetUniverseId); // inclui livros da lixeira desse universo
        book.setUniverseId(targetUniverseId);
        Universes.MergeResult merge = universes.merge(ownerId, current, targetUniverseId);
        return new UniverseMove(targetUniverseId, true, merge.duplicateNames());
    }

    /** Exclusão lógica: o livro some das listas, mas pode ser recuperado pelo suporte. */
    @Transactional
    public void delete(UUID ownerId, UUID bookId) {
        require(ownerId, bookId).setDeletedAt(Instant.now(clock));
    }

    Book require(UUID ownerId, UUID bookId) {
        // Livro de outra pessoa responde 404, não 403: não revela que o id existe
        return bookRepository.findByIdAndOwnerIdAndDeletedAtIsNull(bookId, ownerId)
                .orElseThrow(() -> ApiException.notFound("book_not_found", "Livro não encontrado."));
    }

    private static void validate(BookData data) {
        if (data.kind() == BookKind.FANFIC && (data.fandom() == null || data.fandom().isBlank())) {
            throw ApiException.unprocessable("fandom_required", "Informe o fandom (obra original) da fanfic.");
        }
    }

    private static void apply(Book book, BookData data) {
        book.setTitle(data.title().trim());
        book.setSlug(SlugUtils.toSlug(data.title()));
        book.setSynopsis(data.synopsis());
        book.setKind(data.kind());
        book.setFandom(data.kind() == BookKind.FANFIC ? data.fandom().trim() : null);
    }

    /** {@code language} nulo: pt-BR na criação, sem alteração na edição. */
    /** {@code universeId}: só na criação; nulo cria um universo novo para o livro. */
    public record BookData(String title, String synopsis, BookKind kind, String fandom, BookStatus status,
                           BookLanguage language,
                           String coverDataUrl, UUID universeId) {
    }

    /**
     * @param merged         o universo antigo foi juntado ao novo (o livro era o único dele)
     * @param duplicateNames personagens e lugares com o mesmo nome nos dois universos
     */
    public record UniverseMove(UUID universeId, boolean merged, List<String> duplicateNames) {
    }

    public record BookWithStats(Book book, BookStats stats) {
    }

    /** {@code layout}: a diagramação já lida do JSON guardado. */
    public record BookDetail(Book book, List<Chapter> chapters, BookLayout layout, CoverDesign coverDesign,
                             CoverPdf coverPdf) {

        public BookDetail(Book book, List<Chapter> chapters) {
            this(book, chapters, BookLayout.DEFAULT, null, null);
        }
    }
}
