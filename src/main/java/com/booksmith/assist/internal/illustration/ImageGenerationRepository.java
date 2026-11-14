package com.booksmith.assist.internal.illustration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface ImageGenerationRepository extends JpaRepository<ImageGeneration, UUID> {

    long countByUserIdAndStatusAndCreatedAtAfter(UUID userId, String status, Instant after);

    /** As que contam no limite, mais antigas primeiro: a primeira diz quando volta a haver vaga. */
    List<ImageGeneration> findAllByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(UUID userId, String status,
                                                                                      Instant after);
}
