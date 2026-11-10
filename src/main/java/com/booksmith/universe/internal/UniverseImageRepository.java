package com.booksmith.universe.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UniverseImageRepository extends JpaRepository<UniverseImage, UUID> {

    List<UniverseImage> findAllByUniverseIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID universeId);

    Optional<UniverseImage> findByIdAndDeletedAtIsNull(UUID id);

    long countByUniverseIdAndDeletedAtIsNull(UUID universeId);

    @Query("select x from UniverseImage x where x.deletedAt > :cutoff and x.universeId in "
            + "(select u.id from Universe u where u.ownerId = :ownerId) order by x.deletedAt desc")
    List<UniverseImage> findTrashed(UUID ownerId, Instant cutoff);

    List<UniverseImage> findAllByDeletedAtBefore(Instant cutoff);

    /** Todas as imagens, inclusive as excluídas, passam para outro universo (junção de universos). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UniverseImage i set i.universeId = :into where i.universeId = :from")
    int moveAll(UUID from, UUID into);
}
