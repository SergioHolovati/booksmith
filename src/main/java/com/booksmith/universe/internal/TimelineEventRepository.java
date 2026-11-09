package com.booksmith.universe.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, UUID> {

    List<TimelineEvent> findAllByUniverseIdAndDeletedAtIsNullOrderBySortKeyAsc(UUID universeId);

    Optional<TimelineEvent> findByIdAndDeletedAtIsNull(UUID id);

    /** Linhas [universeId, quantidade], sem os excluídos. */
    @Query("select t.universeId, count(t) from TimelineEvent t "
            + "where t.universeId in :ids and t.deletedAt is null group by t.universeId")
    List<Object[]> countByUniverse(Collection<UUID> ids);

    @Query("select max(t.sortKey) from TimelineEvent t where t.universeId = :universeId")
    BigDecimal maxSortKey(UUID universeId);

    @Query("select min(t.sortKey) from TimelineEvent t where t.universeId = :universeId")
    BigDecimal minSortKey(UUID universeId);

    /** Todos os eventos, inclusive os excluídos, passam para outro universo, deslocados por {@code offset}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TimelineEvent t set t.universeId = :into, t.sortKey = t.sortKey + :offset where t.universeId = :from")
    int moveAll(UUID from, UUID into, BigDecimal offset);
}
