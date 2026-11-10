package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChapterNoteServiceTest {

    private final ChapterNoteRepository repository = mock(ChapterNoteRepository.class);
    private final ChapterService chapters = mock(ChapterService.class);
    private final ChapterNoteService service = new ChapterNoteService(repository, chapters);

    private final UUID owner = UUID.randomUUID();
    private final UUID chapter = UUID.randomUUID();

    ChapterNoteServiceTest() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void noteKeepsTheEditorIdAndTheQuoteCutToSize() {
        UUID id = UUID.randomUUID();
        ChapterNote note = service.create(owner, chapter, id, "  Conferir a data da guerra  ", "x".repeat(800));

        assertThat(note.getId()).isEqualTo(id);
        assertThat(note.getBody()).isEqualTo("Conferir a data da guerra");
        assertThat(note.getQuote()).hasSize(ChapterNoteService.MAX_QUOTE).endsWith("…");
    }

    @Test
    void emptyNoteOrRepeatedIdIsRefused() {
        assertThatThrownBy(() -> service.create(owner, chapter, UUID.randomUUID(), "  ", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("note_invalid"));
        UUID taken = UUID.randomUUID();
        when(repository.existsById(taken)).thenReturn(true);
        assertThatThrownBy(() -> service.create(owner, chapter, taken, "Oi", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("note_exists"));
    }

    @Test
    void someoneElsesNoteIsNotFoundAndResolvingKeepsTheText() {
        ChapterNote note = ChapterNote.builder().id(UUID.randomUUID()).chapterId(chapter).ownerId(owner).body("Rever").build();
        when(repository.findById(note.getId())).thenReturn(Optional.of(note));

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), note.getId(), "outra", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("note_not_found"));
        ChapterNote resolved = service.update(owner, note.getId(), null, true);
        assertThat(resolved.isResolved()).isTrue();
        assertThat(resolved.getBody()).isEqualTo("Rever");
    }
}
