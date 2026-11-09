package com.booksmith.manuscript.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterRepository extends JpaRepository<Chapter, UUID> {

    Optional<Chapter> findByIdAndDeletedAtIsNull(UUID id);

    List<Chapter> findAllByBookIdAndDeletedAtIsNullOrderByPositionAsc(UUID bookId);

    @Query("select coalesce(max(c.position), 0) from Chapter c where c.bookId = :bookId and c.deletedAt is null")
    int maxPosition(UUID bookId);

    @Query("""
            select new com.booksmith.manuscript.internal.BookStats(c.bookId, count(c), coalesce(sum(c.wordCount), 0))
            from Chapter c
            where c.bookId in :bookIds and c.deletedAt is null
            group by c.bookId
            """)
    List<BookStats> statsFor(Collection<UUID> bookIds);

    // ===== Lixeira =====

    /** Capítulos excluídos individualmente, de livros que ainda estão ativos. */
    @Query("""
            select new com.booksmith.manuscript.internal.TrashedChapter(
                c.id, c.title, c.wordCount, b.id, b.title, c.deletedAt)
            from Chapter c, Book b
            where c.bookId = b.id and b.ownerId = :ownerId and b.deletedAt is null
              and c.deletedAt > :cutoff
            order by c.deletedAt desc
            """)
    List<TrashedChapter> findTrashed(UUID ownerId, Instant cutoff);

    Optional<Chapter> findByIdAndDeletedAtAfter(UUID id, Instant cutoff);

    @Modifying
    @Query("""
            delete from Chapter c
            where c.deletedAt < :cutoff
               or c.bookId in (select b.id from Book b where b.deletedAt < :cutoff)
            """)
    int purgeDeletedBefore(Instant cutoff);
}
