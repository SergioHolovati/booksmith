package com.booksmith.universe.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UniverseRepository extends JpaRepository<Universe, UUID> {

    Optional<Universe> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Universe> findAllByOwnerIdOrderByNameAsc(UUID ownerId);

    /** Tira da lixeira (o filtro da entidade esconde o excluído, então a restauração vai direto na tabela). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "update universe.universes set deleted_at = null "
            + "where id = :id and owner_id = :ownerId and deleted_at is not null", nativeQuery = true)
    int restore(@org.springframework.data.repository.query.Param("id") UUID id,
                @org.springframework.data.repository.query.Param("ownerId") UUID ownerId);
}
