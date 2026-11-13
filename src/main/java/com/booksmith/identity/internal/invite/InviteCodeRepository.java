package com.booksmith.identity.internal.invite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface InviteCodeRepository extends JpaRepository<InviteCode, UUID> {

    Optional<InviteCode> findByCode(String code);

    boolean existsByCode(String code);

    List<InviteCode> findAllByOrderByCreatedAtDesc();

    /** Gasta um uso, de uma vez só (dois cadastros ao mesmo tempo não passam do limite). 1 = gastou. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InviteCode c set c.usedCount = c.usedCount + 1
            where c.code = :code and c.usedCount < c.maxUses and (c.expiresAt is null or c.expiresAt > :now)""")
    int use(@Param("code") String code, @Param("now") Instant now);
}
