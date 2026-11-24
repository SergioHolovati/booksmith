package com.booksmith.media.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * As imagens que a moderação separou: as que entraram e esperam o admin olhar ({@code pending}) e as recusadas na
 * hora ({@code blocked}, sem a imagem, que não foi guardada). O admin mantém ({@code kept}) ou remove
 * ({@code removed}: a imagem sai do armazenamento).
 */
public interface ImageReviews {

    /** @param reason nudez, violência, ódio ou ilegível */
    record Review(UUID id, UUID ownerId, String category, String url, double score, String status, String reason,
                  Instant createdAt, Instant decidedAt) {
    }

    /** As pendentes primeiro, depois as últimas decididas e recusadas (até {@code limit}). */
    List<Review> list(int limit);

    /** @return false se não existe ou já foi decidida */
    boolean keep(UUID id, UUID adminId);

    /** Remove a imagem do armazenamento. @return false se não existe ou já foi decidida */
    boolean remove(UUID id, UUID adminId);
}
