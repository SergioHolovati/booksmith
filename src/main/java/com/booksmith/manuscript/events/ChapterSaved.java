package com.booksmith.manuscript.events;

import java.time.Instant;
import java.util.UUID;

/** Publicado a cada salvamento de conteúdo. Consumidor previsto: agente de continuidade (§10). */
public record ChapterSaved(
        UUID chapterId,
        UUID bookId,
        UUID ownerId,
        long version,
        String contentHash,
        Instant savedAt
) {
}
