package com.booksmith.universe.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UniverseRepository extends JpaRepository<Universe, UUID> {

    Optional<Universe> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Universe> findAllByOwnerIdOrderByNameAsc(UUID ownerId);
}
