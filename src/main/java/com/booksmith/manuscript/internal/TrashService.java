package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lixeira (§20.6 da arquitetura, §4.10 do produto): livros e capítulos excluídos ficam 30 dias e voltam com um
 * toque. Depois disso, um job diário apaga de vez (capítulos, versões e livros).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashService {

    public static final Duration RETENTION = Duration.ofDays(30);

    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final BookService bookService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Trash list(UUID ownerId) {
        Instant cutoff = cutoff();
        return new Trash(
                bookRepository.findAllByOwnerIdAndDeletedAtAfterOrderByDeletedAtDesc(ownerId, cutoff),
                chapterRepository.findTrashed(ownerId, cutoff));
    }

    @Transactional
    public Book restoreBook(UUID ownerId, UUID bookId) {
        Book book = bookRepository.findByIdAndOwnerIdAndDeletedAtAfter(bookId, ownerId, cutoff())
                .orElseThrow(TrashService::notFound);
        book.setDeletedAt(null);
        book.setUpdatedAt(Instant.now(clock));
        return book;
    }

    /** O capítulo volta para o fim do livro (a posição antiga pode já ter sido ocupada). */
    @Transactional
    public Chapter restoreChapter(UUID ownerId, UUID chapterId) {
        Chapter chapter = chapterRepository.findByIdAndDeletedAtAfter(chapterId, cutoff())
                .orElseThrow(TrashService::notFound);
        try {
            bookService.require(ownerId, chapter.getBookId());
        } catch (ApiException e) {
            throw notFound(); // de outra pessoa, ou o livro também está na lixeira (restaure o livro)
        }
        chapter.setPosition(chapterRepository.maxPosition(chapter.getBookId()) + 1);
        chapter.setDeletedAt(null);
        return chapterRepository.saveAndFlush(chapter);
    }

    /** Apaga de vez o que passou do prazo. Todo dia às 3h30. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = cutoff();
        int chapters = chapterRepository.purgeDeletedBefore(cutoff); // versões vão junto (on delete cascade)
        int books = bookRepository.purgeDeletedBefore(cutoff);
        if (chapters + books > 0) {
            log.info("Lixeira: {} capítulos e {} livros apagados definitivamente (excluídos antes de {})",
                    chapters, books, cutoff);
        }
    }

    private Instant cutoff() {
        return Instant.now(clock).minus(RETENTION);
    }

    private static ApiException notFound() {
        return ApiException.notFound("trash_item_not_found", "Item não encontrado na lixeira.");
    }

    public record Trash(List<Book> books, List<TrashedChapter> chapters) {
    }
}
