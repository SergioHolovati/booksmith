package com.booksmith.manuscript.internal;

import com.booksmith.common.data.PersonalDataArchive;
import com.booksmith.common.data.PersonalDataContributor;
import com.booksmith.media.api.ImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Baixar tudo": cada livro numa pasta, com os dados completos ({@code livro.json}: metadados e o documento de
 * cada capítulo, que volta a ser importável), o texto puro ({@code texto.txt}) e a capa. O Word legível vem do
 * módulo de exportação, na mesma pasta.
 */
@Component
@RequiredArgsConstructor
class BooksDataContributor implements PersonalDataContributor {

    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final ChapterNoteRepository noteRepository;
    private final ImageStorage imageStorage;
    private final JsonMapper jsonMapper;

    @Override
    public String name() {
        return "livros";
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(UUID ownerId, PersonalDataArchive archive) {
        for (Book book : bookRepository.findAllByOwnerIdAndDeletedAtIsNull(ownerId, Pageable.unpaged())) {
            String folder = "livros/" + PersonalDataArchive.folder(book.getTitle(), book.getId()) + "/";
            List<Chapter> chapters = chapterRepository.findAllByBookIdAndDeletedAtIsNullOrderByPositionAsc(book.getId());

            String cover = null;
            if (book.getCoverUrl() != null) {
                var image = imageStorage.read(book.getCoverUrl()).orElse(null);
                if (image != null) {
                    cover = "capa." + image.extension();
                    archive.bytes(folder + cover, image.bytes());
                }
            }
            List<ChapterData> chapterData = chapters.stream()
                    .map(c -> new ChapterData(c.getId(), c.getPosition(), c.getTitle(), c.getPartTitle(), c.getWordCount(),
                            c.getUpdatedAt(), jsonMapper.readTree(c.getContent()),
                            noteRepository.findAllByChapterIdOrderByCreatedAtAsc(c.getId()).stream()
                                    .map(n -> new NoteData(n.getId(), n.getQuote(), n.getBody(), n.isResolved(),
                                            n.getCreatedAt()))
                                    .toList()))
                    .toList();
            archive.json(folder + "livro.json", new BookData(book.getId(), book.getTitle(), book.getSynopsis(),
                    book.getKind() == null ? null : book.getKind().name(), book.getFandom(),
                    book.getLanguage() == null ? null : book.getLanguage().tag(),
                    book.getStatus() == null ? null : book.getStatus().name(), book.getUniverseId(), cover,
                    book.getCreatedAt(), book.getUpdatedAt(), book.pages(),
                    jsonMapper.readTree(book.getLayout() == null ? "{}" : book.getLayout()),
                    book.getCoverDesign() == null ? null : jsonMapper.readTree(book.getCoverDesign()),
                    jsonMapper.readTree(book.getPublishing() == null ? "{}" : book.getPublishing()), chapterData));
            archive.text(folder + "texto.txt", plainText(book, chapters));
        }
    }

    /** Título, e cada capítulo com o título e o texto, separados por uma linha em branco. */
    static String plainText(Book book, List<Chapter> chapters) {
        StringBuilder text = new StringBuilder(book.getTitle()).append("\n\n");
        for (Chapter chapter : chapters) {
            String title = chapter.getTitle() == null || chapter.getTitle().isBlank()
                    ? "Capítulo " + (chapter.getPosition() + 1) : chapter.getTitle().strip();
            text.append("\n").append(title).append("\n\n").append(chapter.getContentText().strip()).append("\n\n");
        }
        return text.toString();
    }

    /**
     * {@code document}: JSON ProseMirror do capítulo, o formato do editor. {@code notes}: as notas do autor; o
     * trecho de cada uma tem a marca commentAnchor com o mesmo id no documento.
     */
    record ChapterData(UUID id, int position, String title, String partTitle, int wordCount, Instant updatedAt, JsonNode document,
                       List<NoteData> notes) {
    }

    record NoteData(UUID id, String quote, String body, boolean resolved, Instant createdAt) {
    }

    /**
     * {@code cover}: nome do arquivo da capa na pasta do livro. {@code pages}: créditos, dedicatória, epígrafe,
     * agradecimentos e "sobre o autor". {@code layout}: a diagramação do PDF. {@code coverDesign}: o desenho da capa
     * feita no editor (null = não foi feita nele). {@code publishing}: o guia de publicação (destino, ISBNs,
     * palavras-chave e passos feitos).
     */
    record BookData(UUID id, String title, String synopsis, String kind, String fandom, String language, String status,
                    UUID universeId, String cover, Instant createdAt, Instant updatedAt,
                    com.booksmith.manuscript.api.Manuscripts.BookPages pages, JsonNode layout, JsonNode coverDesign,
                    JsonNode publishing, List<ChapterData> chapters) {
    }
}
