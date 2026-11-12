package com.booksmith.identity.internal.mfa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    Optional<MfaRecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    List<MfaRecoveryCode> findAllByUserId(UUID userId);

    void deleteAllByUserId(UUID userId);
}
