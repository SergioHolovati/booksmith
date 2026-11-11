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
    private final Universes universes;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** PDFs das prévias mais recentes: navegar pelas páginas não gera o livro de novo. */
    private static final int PREVIEW_CACHE = 6;

    private final java.util.Map<String, byte[]> previews = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(PREVIEW_CACHE * 2, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, byte[]> eldest) {
                    return size() > PREVIEW_CACHE;
                }
            });

    public record ExportFile(String filename, String contentType, byte[] bytes) {
    }

    /**
     * Prévia do PDF com uma diagramação (salva ou não): as páginas {@code from} a {@code from + count - 1}.
     * O PDF fica no cache enquanto o livro, a diagramação e o autor forem os mesmos.
     *
     * @throws ApiException 404 se o livro não é do usuário; 422 {@code book_empty} se não há capítulos
     */
    public PdfPreview.Preview preview(UUID ownerId, String author, UUID bookId, BookLayout layout, int from, int count) {
        BookContent content = manuscripts.bookContent(ownerId, bookId);
        String key = fingerprint(ownerId + "|" + author + "|" + layout + "|" + content);
        byte[] pdf = previews.get(key);
        if (pdf == null) {
            long start = System.nanoTime();
            pdf = PdfWriter.write(prepare(content, author).withLayout(layout));
            previews.put(key, pdf);
            log.info("Prévia do livro {}: {} KB em {} ms", bookId, pdf.length / 1024, (System.nanoTime() - start) / 1_000_000);
        }
        return PdfPreview.render(pdf, from, count);
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
            chapters.add(new ExportBook.Chapter(BookHtml.chapterTitle(chapter.title(), i + 1),
                    jsonMapper.readTree(chapter.document()),
                    chapter.partTitle() == null || chapter.partTitle().isBlank() ? null : chapter.partTitle().strip()));
        }
        String fandom = content.fanfic()
                ? (content.fandom() == null || content.fandom().isBlank() ? "outra obra" : content.fandom().strip())
                : null;
        var cover = content.coverUrl() == null ? null : imageStorage.read(content.coverUrl()).orElse(null);
        return new ExportBook(content.bookId(), content.title().strip(), author, content.language(),
                content.synopsis(), fandom, cover, Instant.now(clock), chapters, illustrations(content, chapters),
                content.pages() == null ? Manuscripts.BookPages.EMPTY : content.pages(),
                content.layout() == null ? BookLayout.DEFAULT : content.layout());
    }

    /** Imagens das ilustrações, lidas uma vez cada; a que saiu da biblioteca (ou do universo) fica de fora. */
    private java.util.Map<String, ImageStorage.ImageData> illustrations(BookContent content, List<ExportBook.Chapter> chapters) {
        java.util.Map<String, ImageStorage.ImageData> images = new java.util.LinkedHashMap<>();
        if (content.universeId() == null) {
            return images;
        }
        for (ExportBook.Chapter chapter : chapters) {
            for (String id : BookHtml.illustrationIds(chapter.document())) {
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
        }
        return images;
    }

    /** "A Conversão" → "a-conversao.epub". */
    static String filename(String title, ExportFormat format) {
        String slug = SlugUtils.toSlug(title);
        return (slug == null || slug.isBlank() ? "livro" : slug) + "." + format.extension();
    }
}
