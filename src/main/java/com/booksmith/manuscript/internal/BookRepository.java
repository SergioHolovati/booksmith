package com.booksmith.manuscript.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

    Page<Book> findAllByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

    Optional<Book> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

    /** Outros livros ativos no mesmo universo. */
    long countByUniverseIdAndIdNotAndDeletedAtIsNull(UUID universeId, UUID id);

    /** Todos os livros de um universo, inclusive os da lixeira, passam para outro. */
    @Modifying
    @Query("update Book b set b.universeId = :into where b.universeId = :from")
    int moveUniverse(UUID from, UUID into);

    // ===== Lixeira =====

    List<Book> findAllByOwnerIdAndDeletedAtAfterOrderByDeletedAtDesc(UUID ownerId, Instant cutoff);

    Optional<Book> findByIdAndOwnerIdAndDeletedAtAfter(UUID id, UUID ownerId, Instant cutoff);

    @Modifying
    @Query("delete from Book b where b.deletedAt < :cutoff")
    int purgeDeletedBefore(Instant cutoff);
}
