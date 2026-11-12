package com.booksmith.identity.internal.mfa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MfaFactorRepository extends JpaRepository<MfaFactor, UUID> {

    List<MfaFactor> findAllByUserId(UUID userId);

    Optional<MfaFactor> findByUserIdAndType(UUID userId, String type);

    boolean existsByUserIdAndConfirmedAtIsNotNull(UUID userId);

    void deleteAllByUserId(UUID userId);
}
