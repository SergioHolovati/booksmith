package com.booksmith.assist.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SuggestionLogRepository extends JpaRepository<SuggestionLog, UUID> {

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant after);

    /** Os usos na cota: tudo menos as chamadas de dentro da revisão (a revisão conta pela {@link ReviewRun}). */
    long countByUserIdAndKindNotAndCreatedAtAfter(UUID userId, String kind, Instant after);

    List<SuggestionLog> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}
