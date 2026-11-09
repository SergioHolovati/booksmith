package com.booksmith.universe.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UniverseEntityRepository extends JpaRepository<UniverseEntity, UUID> {

    List<UniverseEntity> findAllByUniverseIdAndKindAndDeletedAtIsNullOrderByNameAsc(UUID universeId, String kind);

    Optional<UniverseEntity> findByIdAndKindAndDeletedAtIsNull(UUID id, String kind);

    /** Linhas [universeId, kind, quantidade], sem as excluídas. */
    @Query("select e.universeId, e.kind, count(e) from UniverseEntity e "
            + "where e.universeId in :ids and e.deletedAt is null group by e.universeId, e.kind")
    List<Object[]> countByUniverse(Collection<UUID> ids);

    /** Todas as entidades, inclusive as excluídas, passam para outro universo. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UniverseEntity e set e.universeId = :into where e.universeId = :from")
    int moveAll(UUID from, UUID into);
}
