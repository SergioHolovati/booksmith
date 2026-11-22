package com.booksmith.export.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.common.text.SlugUtils;
import com.booksmith.manuscript.api.BookLayout;
import com.booksmith.manuscript.api.Manuscripts;
import com.booksmith.manuscript.api.Manuscripts.BookContent;
import com.booksmith.media.api.ImageStorage;
import com.booksmith.universe.api.Universes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Gera o arquivo do livro na hora (Fase 1). Sempre em nome do dono: livro de outra pessoa é 404. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final Manuscripts manuscripts;
    private final ImageStorage imageStorage;
    private final com.booksmith.media.api.PdfStorage pdfStorage;
    private final Universes universes;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** PDFs das prévias mais recentes: navegar pelas páginas não gera o livro de novo. */
    private static final int PREVIEW_CACHE = 6;

    private final java.util.Map<String, Rendered> previews = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(PREVIEW_CACHE * 2, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Rendered> eldest) {
                    return size() > PREVIEW_CACHE;
                }
            });

    /** PDF já gerado e quantas páginas tem o miolo (sem a página da capa). */
    private record Rendered(byte[] pdf, int interiorPages) {
    }

    public record ExportFile(String filename, String contentType, byte[] bytes) {
    }

    /**
     * Prévia do PDF com uma diagramação (salva ou não): as páginas {@code from} a {@code from + count - 1}.
     * O PDF fica no cache enquanto o livro, a diagramação e o autor forem os mesmos.
     *
     * @throws ApiException 404 se o livro não é do usuário; 422 {@code book_empty} se não há capítulos
     */
    public PdfPreview.Preview preview(UUID ownerId, String author, UUID bookId, BookLayout layout, int from, int count) {
        return preview(ownerId, author, bookId, layout, from, count, false);
    }

    /** {@code ebook}: no livro ilustrado, as páginas como no e-book (sem sangria, sem número, sem as em branco). */
    public PdfPreview.Preview preview(UUID ownerId, String author, UUID bookId, BookLayout layout, int from, int count,
                                      boolean ebook) {
        return PdfPreview.render(rendered(ownerId, author, manuscripts.bookContent(ownerId, bookId), layout, ebook).pdf(),
                from, count);
    }

    /**
     * As ilustrações do texto na diagramação dada (salva ou não): tamanho na página e DPI para impressão.
     *
     * @throws ApiException 404 se o livro não é do usuário; 422 {@code book_empty} se não há capítulos
     */
    public PrintCheck.Result printCheck(UUID ownerId, String author, UUID bookId, BookLayout layout) {
        BookContent content = manuscripts.bookContent(ownerId, bookId);
        PrintCheck.Result result = PrintCheck.check(prepare(content, author).withLayout(layout));
        return content.pages() == null ? result : result.withPending(content.pages());
    }

    /**
     * Medidas da capa aberta para gráfica: o número de páginas sai do PDF com a diagramação salva.
     *
     * @throws ApiException 404 se o livro não é do usuário; 422 {@code book_empty}, {@code print_profile_invalid},
     *                      {@code print_spine_invalid} ou {@code print_bleed_invalid}
     */
    public PrintCover.Spec printCoverSpec(UUID ownerId, String author, UUID bookId, String profile, Double spineMm,
                                          Double bleedMm) {
        return printCoverSpec(ownerId, author, bookId, profile, spineMm, bleedMm, null);
    }

    /** Idem, com orelhas ({@code flapMm}: a largura de cada uma; só em outra gráfica). */
    public PrintCover.Spec printCoverSpec(UUID ownerId, String author, UUID bookId, String profile, Double spineMm,
                                          Double bleedMm, Double flapMm) {
        BookContent content = manuscripts.bookContent(ownerId, bookId);
        BookLayout layout = content.layout() == null ? BookLayout.DEFAULT : content.layout();
        int pages = rendered(ownerId, author, content, layout, false).interiorPages();
        return PrintCover.spec(PrintCover.Profile.of(profile), layout, pages, spineMm, bleedMm, flapMm)
                .withImported(manuscripts.coverPdf(ownerId, bookId).orElse(null));
    }

    /** PDF da capa aberta, com a imagem desenhada no aparelho nas medidas de {@link #printCoverSpec}. */
    public ExportFile printCover(UUID ownerId, String author, UUID bookId, String profile, Double spineMm,
                                 Double bleedMm, String imageDataUrl) {
        return printCover(ownerId, author, bookId, profile, spineMm, bleedMm, null, imageDataUrl);
    }

    /** Idem, com orelhas. */
    public ExportFile printCover(UUID ownerId, String author, UUID bookId, String profile, Double spineMm,
                                 Double bleedMm, Double flapMm, String imageDataUrl) {
        PrintCover.Spec spec = printCoverSpec(ownerId, author, bookId, profile, spineMm, bleedMm, flapMm);
        var imported = manuscripts.coverPdf(ownerId, bookId).orElse(null);
        byte[] pdf;
        if (imported != null && imported.wrap()) {
            // Capa aberta feita fora: só sai se as medidas baterem, e sai como veio (o original do autor)
            if (!Boolean.TRUE.equals(spec.importedFits())) {
                throw ApiException.unprocessable("print_cover_wrong_size", String.join(" ", spec.warnings()));
            }
            byte[] original = pdfStorage.read(imported.url()).orElseThrow(() ->
                    ApiException.unprocessable("cover_pdf_missing", "O PDF da capa não foi encontrado. Importe de novo."));
            // Sai como o autor fez, só sem a margem das marcas de corte (a medida exata que a gráfica confere)
            pdf = PrintCover.cropToBleed(original, imported, spec);
        } else {
            byte[] front = imported == null ? null : pdfStorage.read(imported.url()).orElse(null);
            pdf = PrintCover.pdf(spec, imageDataUrl, front, imported);
        }
        String title = manuscripts.bookContent(ownerId, bookId).title();
        log.info("Capa para gráfica do livro {}: {} x {} mm, {} páginas, {} KB", bookId, spec.widthMm(), spec.heightMm(),
                spec.pages(), pdf.length / 1024);
        return new ExportFile("capa-" + filename(title, ExportFormat.PDF), ExportFormat.PDF.contentType(), pdf);
    }

    /** Lado maior das ilustrações na prévia (a página da prévia é uma imagem pequena; a dupla tem duas larguras). */
    static final int PREVIEW_IMAGE_SIDE = 2000;

    /** O PDF do livro com uma diagramação, do cache quando nada mudou. */
    private Rendered rendered(UUID ownerId, String author, BookContent content, BookLayout layout, boolean ebook) {
        String key = fingerprint(ownerId + "|" + author + "|" + layout + "|" + ebook + "|" + content);
        Rendered rendered = previews.get(key);
        if (rendered == null) {
            long start = System.nanoTime();
            ExportBook book = prepare(content, author).withLayout(layout).withPreviewImages(PREVIEW_IMAGE_SIDE);
            byte[] pdf = ebook && book.pictureBook() ? PdfWriter.writeEbookPreview(book) : PdfWriter.write(book);
            rendered = new Rendered(pdf, PdfPreview.pageCount(pdf) - (PdfWriter.hasCoverPage(book) ? 1 : 0));
            previews.put(key, rendered);
            log.info("PDF do livro {} para prévia: {} KB em {} ms", content.bookId(), pdf.length / 1024,
                    (System.nanoTime() - start) / 1_000_000);
        }
        return rendered;
    }

    private static String fingerprint(String text) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param author nome que vai na folha de rosto e nos metadados (o nome de exibição do autor)
     * @throws ApiException 404 se o livro não é do usuário; 422 {@code book_empty} se não há capítulos
     */
    public ExportFile export(UUID ownerId, String author, UUID bookId, ExportFormat format) {
        ExportBook book = prepare(manuscripts.bookContent(ownerId, bookId), author);
        long start = System.nanoTime();
        byte[] bytes = switch (format) {
            case EPUB -> EpubWriter.write(book);
            case PDF -> PdfWriter.write(book);
            case DOCX -> DocxWriter.write(book);
        };
        log.info("Exportação {} do livro {}: {} capítulos, {} KB em {} ms", format, bookId, book.chapters().size(),
                bytes.length / 1024, (System.nanoTime() - start) / 1_000_000);
        return new ExportFile(filename(book.title(), format), format.contentType(), bytes);
    }

    private ExportBook prepare(BookContent content, String author) {
        if (content.chapters().isEmpty()) {
            throw ApiException.unprocessable("book_empty",
                    "O livro ainda não tem capítulos. Escreva o primeiro antes de exportar.");
        }
        List<ExportBook.Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < content.chapters().size(); i++) {
            var chapter = content.chapters().get(i);
            var document = jsonMapper.readTree(chapter.document());
            if (content.pictureBook()) {
                // No livro ilustrado, o fundo da página entra como a primeira ilustração (o ePub e o Word mostram)
                document = PictureBookHtml.withBackground(document, chapter.page());
            }
            chapters.add(new ExportBook.Chapter(BookHtml.chapterTitle(chapter.title(), i + 1), document,
                    chapter.partTitle() == null || chapter.partTitle().isBlank() ? null : chapter.partTitle().strip(),
                    content.pictureBook() ? chapter.page() : null, chapter.chapterId()));
        }
        String fandom = content.fanfic()
                ? (content.fandom() == null || content.fandom().isBlank() ? "outra obra" : content.fandom().strip())
                : null;
        var cover = content.coverUrl() == null ? null : imageStorage.read(content.coverUrl()).orElse(null);
        return new ExportBook(content.bookId(), content.title().strip(), author, content.language(),
                content.synopsis(), fandom, cover, Instant.now(clock), chapters, illustrations(content, chapters),
                content.pages() == null ? Manuscripts.BookPages.EMPTY : content.pages().filled(),
                content.layout() == null ? BookLayout.DEFAULT : content.layout(), content.pictureBook());
    }

    /** Imagens das ilustrações, lidas uma vez cada; a que saiu da biblioteca (ou do universo) fica de fora. */
    private java.util.Map<String, ImageStorage.ImageData> illustrations(BookContent content, List<ExportBook.Chapter> chapters) {
        java.util.Map<String, ImageStorage.ImageData> images = new java.util.LinkedHashMap<>();
        if (content.universeId() == null) {
            return images;
        }
        List<String> ids = new ArrayList<>();
        // A foto de quem escreveu (página "sobre"), também da biblioteca do universo do livro
        if (content.pages() != null && content.pages().authorPhotoId() != null) {
            ids.add(content.pages().authorPhotoId().toString());
        }
        // O fundo das páginas que o Booksmith gera no livro ilustrado (créditos, dedicatória, sobre o autor…)
        if (content.layout() != null && content.layout().pagesImageId() != null) {
            ids.add(content.layout().pagesImageId());
        }
        // O fundo próprio de cada página gerada, se o autor escolheu
        if (content.pages() != null) {
            content.pages().extras().stream().map(com.booksmith.manuscript.api.ExtraPage::imageId)
                    .filter(java.util.Objects::nonNull).forEach(ids::add);
        }
        for (ExportBook.Chapter chapter : chapters) {
            ids.addAll(BookHtml.illustrationIds(chapter.document()));
        }
        for (String id : ids) {
            if (images.containsKey(id)) {
                continue;
            }
            try {
                universes.imageUrl(content.universeId(), UUID.fromString(id))
                        .flatMap(imageStorage::read)
                        .ifPresent(image -> images.put(id, image));
            } catch (IllegalArgumentException e) {
                log.debug("Ilustração com id inválido: {}", id);
            }
        }
        return images;
    }

    /** "A Conversão" → "a-conversao.epub". */
    static String filename(String title, ExportFormat format) {
        String slug = SlugUtils.toSlug(title);
        return (slug == null || slug.isBlank() ? "livro" : slug) + "." + format.extension();
    }
}
