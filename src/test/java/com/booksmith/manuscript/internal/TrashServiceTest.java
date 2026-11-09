package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrashServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final ChapterRepository chapterRepository = mock(ChapterRepository.class);
    private final BookService bookService = mock(BookService.class);
    private final TrashService service = new TrashService(bookRepository, chapterRepository, bookService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID ownerId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();

    @Test
    void restoredChapterGoesToTheEndOfTheBook() {
        Chapter chapter = Chapter.builder().id(UUID.randomUUID()).bookId(bookId).position(2)
                .deletedAt(NOW.minusSeconds(3600)).build();
        when(chapterRepository.findByIdAndDeletedAtAfter(eq(chapter.getId()), any())).thenReturn(Optional.of(chapter));
        when(bookService.require(ownerId, bookId)).thenReturn(Book.builder().id(bookId).build());
        when(chapterRepository.maxPosition(bookId)).thenReturn(7);
        when(chapterRepository.saveAndFlush(chapter)).thenReturn(chapter);

        Chapter restored = service.restoreChapter(ownerId, chapter.getId());

        assertThat(restored.getDeletedAt()).isNull();
        assertThat(restored.getPosition()).isEqualTo(8);
    }

    @Test
    void searchesOnlyWithinTheThirtyDayWindow() {
        when(bookRepository.findByIdAndOwnerIdAndDeletedAtAfter(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restoreBook(ownerId, bookId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("trash_item_not_found"));
        verify(bookRepository).findByIdAndOwnerIdAndDeletedAtAfter(bookId, ownerId, NOW.minus(TrashService.RETENTION));
    }

    @Test
    void chapterOfAnotherUserCannotBeRestored() {
        Chapter chapter = Chapter.builder().id(UUID.randomUUID()).bookId(bookId).deletedAt(NOW).build();
        when(chapterRepository.findByIdAndDeletedAtAfter(eq(chapter.getId()), any())).thenReturn(Optional.of(chapter));
        when(bookService.require(ownerId, bookId)).thenThrow(ApiException.notFound("book_not_found", "x"));

        assertThatThrownBy(() -> service.restoreChapter(ownerId, chapter.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("trash_item_not_found"));
    }

    @Test
    void purgeUsesTheRetentionCutoff() {
        service.purgeExpired();

        verify(chapterRepository).purgeDeletedBefore(NOW.minus(TrashService.RETENTION));
        verify(bookRepository).purgeDeletedBefore(NOW.minus(TrashService.RETENTION));
    }
}
