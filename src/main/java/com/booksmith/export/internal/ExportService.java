package com.booksmith.export.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.common.text.SlugUtils;
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

    public record ExportFile(String filename, String contentType, byte[] bytes) {
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
                    jsonMapper.readTree(chapter.document())));
        }
        String fandom = content.fanfic()
                ? (content.fandom() == null || content.fandom().isBlank() ? "outra obra" : content.fandom().strip())
                : null;
        var cover = content.coverUrl() == null ? null : imageStorage.read(content.coverUrl()).orElse(null);
        return new ExportBook(content.bookId(), content.title().strip(), author, content.language(),
                content.synopsis(), fandom, cover, Instant.now(clock), chapters, illustrations(content, chapters));
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
                    universes.imageUrl(content.universeId(), java.util.UUID.fromString(id))
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
