package com.booksmith.universe.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RelationRepository extends JpaRepository<Relation, UUID> {

    List<Relation> findAllByUniverseIdAndDeletedAtIsNull(UUID universeId);

    Optional<Relation> findByIdAndDeletedAtIsNull(UUID id);

    /** Excluídos depois de {@code cutoff}, dos universos do usuário, mais recentes primeiro (lixeira). */
    @Query("select x from Relation x where x.deletedAt > :cutoff and x.universeId in "
            + "(select u.id from Universe u where u.ownerId = :ownerId) order by x.deletedAt desc")
    List<Relation> findTrashed(UUID ownerId, java.time.Instant cutoff);

    /** Apaga de vez as relações vencidas na lixeira e as de personagens que vão ser apagados de vez. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from Relation r
            where r.deletedAt < :cutoff
               or r.fromId in (select e.id from UniverseEntity e where e.deletedAt < :cutoff)
               or r.toId in (select e.id from UniverseEntity e where e.deletedAt < :cutoff)
            """)
    int purgeDeletedBefore(java.time.Instant cutoff);

    /** "Desde" e "até" que apontam para eventos que vão ser apagados de vez ficam vazios. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Relation r set r.sinceEventId = null where r.sinceEventId in "
            + "(select t.id from TimelineEvent t where t.deletedAt < :cutoff)")
    int clearSinceBefore(java.time.Instant cutoff);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Relation r set r.untilEventId = null where r.untilEventId in "
            + "(select t.id from TimelineEvent t where t.deletedAt < :cutoff)")
    int clearUntilBefore(java.time.Instant cutoff);

    /** Todas as relações, inclusive as excluídas, passam para outro universo (junção de universos). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Relation r set r.universeId = :into where r.universeId = :from")
    int moveAll(UUID from, UUID into);
}
