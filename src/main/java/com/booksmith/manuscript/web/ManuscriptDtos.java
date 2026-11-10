package com.booksmith.manuscript.web;

import com.booksmith.manuscript.internal.Book;
import com.booksmith.manuscript.internal.BookKind;
import com.booksmith.manuscript.internal.BookLanguage;
import com.booksmith.manuscript.internal.BookService;
import com.booksmith.manuscript.internal.BookStats;
import com.booksmith.manuscript.internal.BookStatus;
import com.booksmith.manuscript.internal.Chapter;
import com.booksmith.manuscript.internal.ChapterService;
import com.booksmith.manuscript.internal.ChapterVersion;
import com.booksmith.manuscript.internal.TrashService;
import com.booksmith.manuscript.internal.TrashedChapter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ManuscriptDtos {

    private ManuscriptDtos() {
    }

    record BookRequest(
            @NotBlank(message = "Dê um título ao livro.") @Size(max = 200) String title,
            @Size(max = 4000) String synopsis,
            @NotNull(message = "Escolha se é uma história original ou fanfic.") BookKind kind,
            @Size(max = 200) String fandom,
            BookStatus status,
            @Size(max = 10) String language,
            String cover,
            UUID universeId) {

        BookService.BookData toData() {
            String coverDataUrl = cover != null && cover.startsWith("data:image") ? cover : null;
            BookLanguage bookLanguage = language == null || language.isBlank() ? null : BookLanguage.fromTag(language);
            return new BookService.BookData(title, synopsis, kind, fandom, status, bookLanguage, coverDataUrl, universeId);
        }
    }

    record BookSummary(UUID id, String title, String synopsis, BookKind kind, String fandom, String language,
                       String coverUrl, BookStatus status, UUID universeId, long chapterCount, long wordCount,
                       Instant updatedAt) {

        static BookSummary from(Book book, BookStats stats) {
            return new BookSummary(book.getId(), book.getTitle(), book.getSynopsis(), book.getKind(),
                    book.getFandom(), book.getLanguage().tag(), book.getCoverUrl(), book.getStatus(), book.getUniverseId(),
                    stats.chapterCount(),
                    stats.wordCount(), book.getUpdatedAt());
        }
    }

    record BookDetailResponse(UUID id, String title, String synopsis, BookKind kind, String fandom,
                              String language, String coverUrl, BookStatus status, UUID universeId, Instant createdAt,
                              Instant updatedAt, List<ChapterSummary> chapters) {

        static BookDetailResponse from(BookService.BookDetail detail) {
            Book book = detail.book();
            return new BookDetailResponse(book.getId(), book.getTitle(), book.getSynopsis(), book.getKind(),
                    book.getFandom(), book.getLanguage().tag(), book.getCoverUrl(), book.getStatus(),
                    book.getUniverseId(), book.getCreatedAt(),
                    book.getUpdatedAt(), detail.chapters().stream().map(ChapterSummary::from).toList());
        }
    }

    /** {@code partTitle}: este capítulo abre uma parte do livro (null = continua a anterior). */
    record ChapterSummary(UUID id, String title, int position, int wordCount, long version, Instant updatedAt,
                          String partTitle) {

        static ChapterSummary from(Chapter chapter) {
            return new ChapterSummary(chapter.getId(), chapter.getTitle(), chapter.getPosition(),
                    chapter.getWordCount(), chapter.getVersion(), chapter.getUpdatedAt(), chapter.getPartTitle());
        }
    }

    /** {@code language}: idioma do livro, para o editor ajustar o corretor ortográfico. */
    record ChapterResponse(UUID id, UUID bookId, String title, int position, String language, JsonNode content,
                           int wordCount, String contentHash, long version, Instant updatedAt) {

        static ChapterResponse from(ChapterService.ChapterWithBook view, JsonMapper mapper) {
            Chapter chapter = view.chapter();
            return new ChapterResponse(chapter.getId(), chapter.getBookId(), chapter.getTitle(),
                    chapter.getPosition(), view.book().getLanguage().tag(), mapper.readTree(chapter.getContent()),
                    chapter.getWordCount(), chapter.getContentHash(), chapter.getVersion(), chapter.getUpdatedAt());
        }
    }

    record SaveResult(long version, int wordCount, String contentHash, Instant updatedAt) {

        static SaveResult from(Chapter chapter) {
            return new SaveResult(chapter.getVersion(), chapter.getWordCount(), chapter.getContentHash(),
                    chapter.getUpdatedAt());
        }
    }

    record CreateChapterRequest(@Size(max = 200) String title) {
    }

    record RenameChapterRequest(@NotBlank @Size(max = 200) String title) {
    }

    record SaveContentRequest(@NotNull JsonNode content) {
    }

    record ReorderRequest(@NotEmpty List<@NotNull UUID> chapterIds) {
    }

    // ===== Histórico de versões =====

    record SaveVersionRequest(@Size(max = 120) String label) {
    }

    record VersionSummary(UUID id, long chapterVersion, ChapterVersion.Reason reason, String label, String title,
                          int wordCount, Instant createdAt) {

        static VersionSummary from(ChapterVersion version) {
            return new VersionSummary(version.getId(), version.getChapterVersion(), version.getReason(),
                    version.getLabel(), version.getTitle(), version.getWordCount(), version.getCreatedAt());
        }
    }

    record VersionDetail(UUID id, long chapterVersion, ChapterVersion.Reason reason, String label, String title,
                         int wordCount, Instant createdAt, JsonNode content) {

        static VersionDetail from(ChapterVersion version, JsonMapper mapper) {
            return new VersionDetail(version.getId(), version.getChapterVersion(), version.getReason(),
                    version.getLabel(), version.getTitle(), version.getWordCount(), version.getCreatedAt(),
                    mapper.readTree(version.getContent()));
        }
    }

    // ===== Lixeira =====

    enum TrashItemType { BOOK, CHAPTER }

    /** {@code expiresAt}: quando o item será apagado de vez. */
    record TrashItem(TrashItemType type, UUID id, String title, UUID bookId, String bookTitle, Integer wordCount,
                     Instant deletedAt, Instant expiresAt) {

        static TrashItem from(Book book) {
            return new TrashItem(TrashItemType.BOOK, book.getId(), book.getTitle(), book.getId(), book.getTitle(),
                    null, book.getDeletedAt(), book.getDeletedAt().plus(TrashService.RETENTION));
        }

        static TrashItem from(TrashedChapter chapter) {
            return new TrashItem(TrashItemType.CHAPTER, chapter.id(), chapter.title(), chapter.bookId(),
                    chapter.bookTitle(), chapter.wordCount(), chapter.deletedAt(),
                    chapter.deletedAt().plus(TrashService.RETENTION));
        }
    }
}
