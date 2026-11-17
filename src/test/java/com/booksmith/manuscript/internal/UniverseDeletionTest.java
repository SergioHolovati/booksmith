package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.ImageStorage;
import com.booksmith.media.api.PdfStorage;
import com.booksmith.universe.api.Universes;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Excluir universo: com livros, recusa listando-os; confirmando, os livros vão juntos para a lixeira. */
class UniverseDeletionTest {

    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final Universes universes = mock(Universes.class);
    private final BookService service = new BookService(bookRepository, mock(ChapterRepository.class),
            mock(ImageStorage.class), universes, Clock.fixed(NOW, ZoneOffset.UTC), JsonMapper.builder().build(),
            mock(PdfStorage.class));

    private final UUID ownerId = UUID.randomUUID();
    private final UUID universeId = UUID.randomUUID();

    @Test
    void universeWithoutBooksGoesToTheTrash() {
        when(bookRepository.findAllByOwnerIdAndUniverseIdAndDeletedAtIsNullOrderByCreatedAtAsc(ownerId, universeId))
                .thenReturn(List.of());

        assertThat(service.deleteUniverse(ownerId, universeId, false)).isEmpty();

        verify(universes).requireOwned(ownerId, universeId);
        verify(universes).trash(ownerId, universeId);
    }

    @Test
    void universeWithBooksIsRefusedListingThem() {
        Book book = Book.builder().id(UUID.randomUUID()).title("A Ilha").build();
        when(bookRepository.findAllByOwnerIdAndUniverseIdAndDeletedAtIsNullOrderByCreatedAtAsc(ownerId, universeId))
                .thenReturn(List.of(book));

        assertThatThrownBy(() -> service.deleteUniverse(ownerId, universeId, false))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("universe_has_books");
                    assertThat(e.getProperties().get("books"))
                            .isEqualTo(List.of(new BookService.LinkedBook(book.getId(), "A Ilha")));
                });
        assertThat(book.getDeletedAt()).isNull();
        verify(universes, never()).trash(any(), any());
    }

    @Test
    void confirmingTakesTheBooksToTheTrashToo() {
        Book first = Book.builder().id(UUID.randomUUID()).title("A Ilha").build();
        Book second = Book.builder().id(UUID.randomUUID()).title("O Farol").build();
        when(bookRepository.findAllByOwnerIdAndUniverseIdAndDeletedAtIsNullOrderByCreatedAtAsc(ownerId, universeId))
                .thenReturn(List.of(first, second));

        List<BookService.LinkedBook> trashed = service.deleteUniverse(ownerId, universeId, true);

        assertThat(trashed).extracting(BookService.LinkedBook::title).containsExactly("A Ilha", "O Farol");
        assertThat(first.getDeletedAt()).isEqualTo(NOW);
        assertThat(second.getDeletedAt()).isEqualTo(NOW);
        verify(universes).trash(ownerId, universeId);
    }

    @Test
    void anotherWritersUniverseIsNotFound() {
        org.mockito.Mockito.doThrow(ApiException.notFound("universe_not_found", "x"))
                .when(universes).requireOwned(ownerId, universeId);

        assertThatThrownBy(() -> service.deleteUniverse(ownerId, universeId, true))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("universe_not_found"));
        verify(universes, never()).trash(any(), any());
    }
}
