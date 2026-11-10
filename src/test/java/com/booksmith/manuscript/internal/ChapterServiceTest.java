package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.events.ChapterSaved;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChapterServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final BookService bookService = mock(BookService.class);
    private final ChapterRepository chapterRepository = mock(ChapterRepository.class);
    private final ChapterVersionRepository versionRepository = mock(ChapterVersionRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
    private final ChapterHistory history = new ChapterHistory(versionRepository, clock);

    private final WritingStatsService writingStats = mock(WritingStatsService.class);
    private final ChapterService service = new ChapterService(bookService, chapterRepository, versionRepository,
            history, writingStats, mapper, events, clock);

    private final UUID ownerId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private Chapter chapter;

    @BeforeEach
    void setUp() {
        chapter = Chapter.builder().id(UUID.randomUUID()).bookId(bookId).position(1).title("Capítulo 1").version(3).build();
        chapter.applyDocument(ManuscriptDocument.empty(mapper));

        when(chapterRepository.findByIdAndDeletedAtIsNull(chapter.getId())).thenReturn(Optional.of(chapter));
        when(bookService.require(ownerId, bookId)).thenReturn(Book.builder().id(bookId).ownerId(ownerId).build());
        when(chapterRepository.saveAndFlush(any(Chapter.class))).thenAnswer(invocation -> {
            Chapter saved = invocation.getArgument(0);
            saved.setVersion(saved.getVersion() + 1); // o que o Hibernate faz no flush
            return saved;
        });
        when(versionRepository.save(any(ChapterVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private JsonNode doc(String text) {
        return mapper.readTree("""
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_p1"},
                  "content":[{"type":"text","text":"%s"}]}]}""".formatted(text));
    }

    @Test
    void savingOverExistingTextKeepsThePreviousStateInHistory() {
        service.saveContent(ownerId, chapter.getId(), 3, doc("Primeira versão do texto"));
        // Capítulo vazio não gera versão
        verify(versionRepository, never()).save(any());

        service.saveContent(ownerId, chapter.getId(), 4, doc("Texto reescrito por completo"));

        ArgumentCaptor<ChapterVersion> captor = ArgumentCaptor.forClass(ChapterVersion.class);
        verify(versionRepository).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo(ChapterVersion.Reason.AUTOMATIC);
        assertThat(captor.getValue().getContent()).contains("Primeira versão do texto");
        assertThat(captor.getValue().getChapterVersion()).isEqualTo(4);
    }

    @Test
    void restoreBringsBackOldContentAndKeepsCurrentStateFirst() {
        service.saveContent(ownerId, chapter.getId(), 3, doc("Texto atual"));
        var old = ManuscriptDocument.parse(doc("Texto antigo"), mapper);
        var version = ChapterVersion.builder().id(UUID.randomUUID()).chapterId(chapter.getId())
                .content(old.json()).wordCount(old.wordCount()).createdAt(Instant.now(clock)).build();
        when(versionRepository.findByIdAndChapterId(version.getId(), chapter.getId())).thenReturn(Optional.of(version));

        var restored = service.restoreVersion(ownerId, chapter.getId(), version.getId(), 4);

        assertThat(restored.chapter().getContentText()).isEqualTo("Texto antigo");
        assertThat(restored.chapter().getVersion()).isEqualTo(5);
        ArgumentCaptor<ChapterVersion> captor = ArgumentCaptor.forClass(ChapterVersion.class);
        verify(versionRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(v -> {
            assertThat(v.getReason()).isEqualTo(ChapterVersion.Reason.BEFORE_RESTORE);
            assertThat(v.getContent()).contains("Texto atual");
        });
    }

    @Test
    void restoreWithStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.restoreVersion(ownerId, chapter.getId(), UUID.randomUUID(), 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void versionOfAnotherChapterIsNotFound() {
        when(versionRepository.findByIdAndChapterId(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getVersion(ownerId, chapter.getId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("version_not_found"));
    }

    @Test
    void savesContentAndPublishesEvent() {
        var content = mapper.readTree("""
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_p1"},
                  "content":[{"type":"text","text":"Era uma vez"}]}]}""");

        Chapter saved = service.saveContent(ownerId, chapter.getId(), 3, content);

        assertThat(saved.getVersion()).isEqualTo(4);
        assertThat(saved.getWordCount()).isEqualTo(3);
        assertThat(saved.getContentText()).isEqualTo("Era uma vez");
        verify(events).publishEvent(any(ChapterSaved.class));
        // As 3 palavras novas entram na meta do dia
        verify(writingStats).record(ownerId, 3, WritingStatsService.DEFAULT_ZONE);
    }

    @Test
    void rejectsStaleVersionWithCurrentVersion() {
        var content = mapper.readTree(chapter.getContent());

        assertThatThrownBy(() -> service.saveContent(ownerId, chapter.getId(), 2, content))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getProperties()).containsEntry("currentVersion", 3L);
                });
        verify(chapterRepository, never()).saveAndFlush(any());
    }

    @Test
    void unchangedContentDoesNotCreateNewVersion() {
        Chapter result = service.saveContent(ownerId, chapter.getId(), 3, mapper.readTree(chapter.getContent()));

        assertThat(result.getVersion()).isEqualTo(3);
        verify(chapterRepository, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void getReturnsChapterWithItsBookForTheEditorLanguage() {
        when(bookService.require(ownerId, bookId))
                .thenReturn(Book.builder().id(bookId).ownerId(ownerId).language(BookLanguage.EN).build());

        var view = service.get(ownerId, chapter.getId());

        assertThat(view.chapter()).isSameAs(chapter);
        assertThat(view.book().getLanguage()).isEqualTo(BookLanguage.EN);
    }

    @Test
    void chapterOfAnotherUserLooksLikeNotFound() {
        UUID intruder = UUID.randomUUID();
        when(bookService.require(intruder, bookId)).thenThrow(ApiException.notFound("book_not_found", "x"));

        assertThatThrownBy(() -> service.get(intruder, chapter.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("chapter_not_found"));
    }

    @Test
    void reorderRequiresExactlyTheCurrentChapters() {
        Chapter second = Chapter.builder().id(UUID.randomUUID()).bookId(bookId).position(2).build();
        when(chapterRepository.findAllByBookIdAndDeletedAtIsNullOrderByPositionAsc(bookId))
                .thenReturn(List.of(chapter, second));

        assertThatThrownBy(() -> service.reorder(ownerId, bookId, List.of(second.getId())))
                .isInstanceOf(ApiException.class);

        var reordered = service.reorder(ownerId, bookId, List.of(second.getId(), chapter.getId()));
        assertThat(reordered).extracting(Chapter::getPosition).containsExactly(1, 2);
        assertThat(second.getPosition()).isEqualTo(1);
        assertThat(chapter.getPosition()).isEqualTo(2);
    }
}
